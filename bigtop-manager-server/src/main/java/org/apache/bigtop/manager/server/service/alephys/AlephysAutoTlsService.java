/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.bigtop.manager.server.service.alephys;

import org.apache.bigtop.manager.common.constants.MessageConstants;
import org.apache.bigtop.manager.common.enums.Command;
import org.apache.bigtop.manager.common.utils.JsonUtils;
import org.apache.bigtop.manager.dao.po.HostPO;
import org.apache.bigtop.manager.dao.po.ServiceConfigPO;
import org.apache.bigtop.manager.dao.po.ServicePO;
import org.apache.bigtop.manager.dao.repository.HostDao;
import org.apache.bigtop.manager.dao.repository.ServiceConfigDao;
import org.apache.bigtop.manager.dao.repository.ServiceDao;
import org.apache.bigtop.manager.grpc.generated.JobCacheReply;
import org.apache.bigtop.manager.grpc.generated.JobCacheRequest;
import org.apache.bigtop.manager.grpc.generated.JobCacheServiceGrpc;
import org.apache.bigtop.manager.grpc.payload.JobCachePayload;
import org.apache.bigtop.manager.server.enums.ApiExceptionEnum;
import org.apache.bigtop.manager.server.enums.CommandLevel;
import org.apache.bigtop.manager.server.exception.ApiException;
import org.apache.bigtop.manager.server.grpc.GrpcClient;
import org.apache.bigtop.manager.server.model.dto.CommandDTO;
import org.apache.bigtop.manager.server.model.dto.command.ServiceCommandDTO;
import org.apache.bigtop.manager.server.service.CommandService;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CM-style AutoTLS: issue CA, distribute PEMs+keystores to agents, enable Manager HTTPS/UI,
 * and wire Hadoop SSL configs + Configure job.
 */
@Slf4j
@Service
public class AlephysAutoTlsService {

    public static final String STORE_PASS = "beatTls2026";

    @Resource
    private AlephysStore store;

    @Resource
    private HostDao hostDao;

    @Resource
    private ServiceDao serviceDao;

    @Resource
    private ServiceConfigDao serviceConfigDao;

    @Resource
    private CommandService commandService;

    @SuppressWarnings("unchecked")
    public Map<String, Object> enable(Map<String, Object> body) {
        String agentDir = AlephysStore.AGENT_TLS_DIR;
        boolean regenerateCa = false;
        String scope = "current";
        Map<String, Object> targets = defaultTargets();
        if (body != null) {
            Object dir = body.get("agentTlsDir");
            if (dir != null && !String.valueOf(dir).isBlank()) {
                agentDir = String.valueOf(dir).trim();
            }
            regenerateCa = bool(body.get("regenerateCa"));
            if (body.get("scope") != null) {
                scope = String.valueOf(body.get("scope"));
            }
            Object t = body.get("targets");
            if (t instanceof Map<?, ?> m) {
                targets.clear();
                m.forEach((k, v) -> targets.put(String.valueOf(k), v));
            }
        }
        // CM-style: if client omitted targets, enable everything
        if (targets.isEmpty()) {
            targets.putAll(defaultTargets());
        }

        if (regenerateCa) {
            try {
                Files.deleteIfExists(AlephysStore.TLS_DIR.resolve("ca.crt"));
                Files.deleteIfExists(AlephysStore.TLS_DIR.resolve("ca.key"));
                Files.deleteIfExists(AlephysStore.TLS_DIR.resolve("ca.srl"));
            } catch (Exception ignored) {
                // continue
            }
        }

        store.initTls();
        issueHostCerts();
        Path caCrt = AlephysStore.TLS_DIR.resolve("ca.crt");
        if (!Files.isRegularFile(caCrt)) {
            log.error("AutoTLS Finish aborted: CA missing at {}", caCrt);
            throw new ApiException(ApiExceptionEnum.OPERATION_FAILED, "CA certificate missing at " + caCrt);
        }

        Path trustP12 = AlephysStore.TLS_DIR.resolve("truststore.p12");
        try {
            buildTruststore(caCrt, trustP12);
        } catch (Exception e) {
            log.error("AutoTLS Finish aborted: truststore build failed", e);
            throw new ApiException(
                    ApiExceptionEnum.OPERATION_FAILED,
                    "Truststore build failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        boolean pushAgents = bool(targets.getOrDefault("agents", true))
                || bool(targets.getOrDefault("agentGrpc", true))
                || bool(targets.getOrDefault("services", true));

        List<Map<String, Object>> rows = new ArrayList<>();
        int ok = 0;
        int fail = 0;
        if (pushAgents) {
            String caPem;
            String trustB64;
            try {
                caPem = Files.readString(caCrt);
                trustB64 = Base64.getEncoder().encodeToString(Files.readAllBytes(trustP12));
            } catch (Exception e) {
                log.error("AutoTLS Finish aborted: cannot read CA/truststore", e);
                throw new ApiException(
                        ApiExceptionEnum.OPERATION_FAILED,
                        "Cannot read CA/truststore: "
                                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            List<HostPO> hosts = hostDao.findAll();
            if (hosts == null) {
                hosts = List.of();
            }
            for (HostPO host : hosts) {
                Map<String, Object> row = pushHost(host, agentDir, caPem, trustB64);
                rows.add(row);
                if (Boolean.TRUE.equals(row.get("ok"))) {
                    ok++;
                } else {
                    fail++;
                }
            }
            store.writeDistributeStatus(rows);
        }

        Map<String, Object> enableResult = new LinkedHashMap<>();
        boolean mgr = bool(targets.getOrDefault("managerServer", true))
                || bool(targets.getOrDefault("managerUi", true));
        boolean scheduleManagerRestart = false;
        if (mgr) {
            Map<String, Object> mgrResult = enableManagerHttps(false);
            enableResult.put("managerHttps", mgrResult);
            scheduleManagerRestart = Boolean.TRUE.equals(mgrResult.get("ok"));
        }
        if (bool(targets.getOrDefault("services", true))) {
            enableResult.put("services", enableServiceTls(agentDir));
        }
        if (bool(targets.getOrDefault("agentGrpc", true))) {
            enableResult.put("agentGrpc", "identity material on agents; channel stays gRPC until mutual-TLS cutover");
        }

        Map<String, Object> wizard = new LinkedHashMap<>();
        wizard.put("updatedAt", Instant.now().toString());
        wizard.put("agentTlsDir", agentDir);
        wizard.put("scope", scope);
        wizard.put("regenerateCa", regenerateCa);
        wizard.put("storePassword", STORE_PASS);
        wizard.put("targets", targets);
        wizard.put("enabled", enableResult);
        wizard.put(
                "note",
                "AutoTLS enabled like CM: agents have PEMs+keystores; Manager UI/API uses HTTPS; Hadoop SSL configs applied via Configure.");
        store.writeTlsWizard(wizard);

        Map<String, Object> out = store.tlsStatus();
        out.put("distributedOk", ok);
        out.put("distributedFail", fail);
        out.put("distributedTotal", rows.size());
        out.put("wizard", wizard);
        out.put("hosts", rows.isEmpty() ? out.get("hosts") : rows);
        out.put("enabled", enableResult);
        out.put("managerHttps", mgr);
        out.put("servicesTls", bool(targets.getOrDefault("services", true)));

        // Restart AFTER response is built — never kill the Finish HTTP call mid-flight
        if (scheduleManagerRestart) {
            scheduleManagerRestart();
            out.put("managerRestart", "scheduled-in-12s");
        }
        return out;
    }

    /**
     * Disable Manager AutoTLS HTTPS and restore plain HTTP :8080.
     * Keeps lab CA / host PEMs under var/alephys/tls (agents can keep material until redistributed).
     */
    public Map<String, Object> disable() {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Path confTls = managerHome().resolve("conf/tls");
            Files.deleteIfExists(confTls.resolve("server.p12"));
            Files.deleteIfExists(confTls.resolve("truststore.p12"));
            Files.deleteIfExists(confTls.resolve("https.port"));
            Files.deleteIfExists(confTls.resolve("http.disabled"));
            // Also clear legacy path if distinct
            Path legacy = Path.of("/opt/bigtop-manager-server/conf/tls");
            if (!legacy.equals(confTls)) {
                Files.deleteIfExists(legacy.resolve("server.p12"));
                Files.deleteIfExists(legacy.resolve("truststore.p12"));
                Files.deleteIfExists(legacy.resolve("https.port"));
                Files.deleteIfExists(legacy.resolve("http.disabled"));
            }

            Map<String, Object> wizard = new LinkedHashMap<>();
            Object prev = store.readTlsWizard();
            if (prev instanceof Map<?, ?> m) {
                m.forEach((k, v) -> wizard.put(String.valueOf(k), v));
            }
            wizard.put("updatedAt", Instant.now().toString());
            wizard.put("managerHttpsDisabled", true);
            wizard.put(
                    "note",
                    "Manager AutoTLS disabled — plain HTTP http://10.1.0.191:8080/ui/ restored after restart.");
            wizard.put("enabled", Map.of("managerHttps", Map.of("ok", false, "httpDisabled", false)));
            store.writeTlsWizard(wizard);

            r.put("ok", true);
            r.put("httpDisabled", false);
            r.put("httpUrl", "http://10.1.0.191:8080/ui/");
            r.put("httpsUrl", null);
            r.put("note", "Manager HTTPS removed. Restarting to restore HTTP :8080.");
            scheduleManagerRestart();
            r.put("managerRestart", "scheduled-in-12s");
            r.putAll(store.tlsStatus());
            r.put("ok", true);
            r.put("httpUrl", "http://10.1.0.191:8080/ui/");
        } catch (Exception e) {
            log.error("AutoTLS disable failed", e);
            throw new ApiException(
                    ApiExceptionEnum.OPERATION_FAILED,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return r;
    }

    private Map<String, Object> pushHost(HostPO host, String agentDir, String caPem, String trustB64) {
        Map<String, Object> row = new LinkedHashMap<>();
        String hostname = host.getHostname();
        row.put("hostname", hostname);
        row.put("ipv4", host.getIpv4());
        row.put("grpcPort", host.getGrpcPort());
        row.put("path", agentDir);
        row.put("at", Instant.now().toString());
        try {
            Path key = AlephysStore.TLS_DIR.resolve("hosts").resolve(hostname + ".key");
            Path crt = AlephysStore.TLS_DIR.resolve("hosts").resolve(hostname + ".crt");
            if (!Files.isRegularFile(key) || !Files.isRegularFile(crt)) {
                row.put("ok", false);
                row.put("message", "missing issued PEM for hostname");
                return row;
            }
            Path hostP12 = AlephysStore.TLS_DIR.resolve("hosts").resolve(hostname + ".p12");
            buildHostPkcs12(crt, key, AlephysStore.TLS_DIR.resolve("ca.crt"), hostP12);
            Integer port = host.getGrpcPort() == null ? 8835 : host.getGrpcPort();
            JobCachePayload payload = new JobCachePayload();
            payload.setTlsDistributeOnly(true);
            payload.setTlsTargetDir(agentDir);
            payload.setTlsHostname(hostname);
            payload.setTlsCaCrt(caPem);
            payload.setTlsHostCrt(Files.readString(crt));
            payload.setTlsHostKey(Files.readString(key));
            payload.setTlsKeystoreB64(Base64.getEncoder().encodeToString(Files.readAllBytes(hostP12)));
            payload.setTlsTruststoreB64(trustB64);
            payload.setTlsStorePassword(STORE_PASS);
            payload.setTlsWriteJks(true);
            payload.setClusterId(host.getClusterId() == null ? 0L : host.getClusterId());

            JobCacheRequest request = JobCacheRequest.newBuilder()
                    .setJobId(0L)
                    .setPayload(JsonUtils.writeAsString(payload))
                    .build();
            JobCacheServiceGrpc.JobCacheServiceBlockingStub stub = GrpcClient.getBlockingStub(
                    hostname, port, JobCacheServiceGrpc.JobCacheServiceBlockingStub.class);
            JobCacheReply reply = stub.save(request);
            boolean success = reply != null && reply.getCode() == MessageConstants.SUCCESS_CODE;
            row.put("ok", success);
            row.put("message", success ? "distributed PEM+keystore" : "agent returned non-success");
        } catch (Exception e) {
            row.put("ok", false);
            row.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return row;
    }

    private Map<String, Object> enableManagerHttps() {
        return enableManagerHttps(true);
    }

    /** @param scheduleRestartImmediately legacy path; Finish flow uses false then schedules after HTTP reply */
    private Map<String, Object> enableManagerHttps(boolean scheduleRestartImmediately) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Path home = managerHome();
            Path confTls = home.resolve("conf/tls");
            Files.createDirectories(confTls);
            // Prefer the server host cert; fall back to first host
            String serverHost = Files.readString(Path.of("/etc/hostname")).trim();
            Path hostCrt = AlephysStore.TLS_DIR.resolve("hosts").resolve(serverHost + ".crt");
            Path hostKey = AlephysStore.TLS_DIR.resolve("hosts").resolve(serverHost + ".key");
            if (!Files.isRegularFile(hostCrt)) {
                List<HostPO> hosts = hostDao.findAll();
                if (hosts != null && !hosts.isEmpty()) {
                    String hn = hosts.get(0).getHostname();
                    hostCrt = AlephysStore.TLS_DIR.resolve("hosts").resolve(hn + ".crt");
                    hostKey = AlephysStore.TLS_DIR.resolve("hosts").resolve(hn + ".key");
                    serverHost = hn;
                }
            }
            if (!Files.isRegularFile(hostCrt) || !Files.isRegularFile(hostKey)) {
                r.put("ok", false);
                r.put("message", "Host certificate missing for " + serverHost + " under " + AlephysStore.TLS_DIR);
                return r;
            }
            Path serverP12 = confTls.resolve("server.p12");
            buildHostPkcs12(hostCrt, hostKey, AlephysStore.TLS_DIR.resolve("ca.crt"), serverP12);
            Files.copy(
                    AlephysStore.TLS_DIR.resolve("truststore.p12"),
                    confTls.resolve("truststore.p12"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Pin HTTPS on 8083. Never 8081 (agent) or 8082 (YARN Query UI).
            // Plain HTTP :8080 is disabled while keystore is present (see BeatHttpsConnectorConfig).
            int httpsPort = 8083;
            Files.createDirectories(org.apache.bigtop.manager.server.config.BeatHttpsConnectorConfig.TLS_DIR);
            Files.writeString(
                    org.apache.bigtop.manager.server.config.BeatHttpsConnectorConfig.PORT_FILE,
                    String.valueOf(httpsPort));
            Files.writeString(
                    org.apache.bigtop.manager.server.config.BeatHttpsConnectorConfig.HTTP_DISABLED_FLAG, "true\n");

            // Strip any old "ssl on main port" block from application.yml
            Path yml = home.resolve("conf/application.yml");
            if (Files.isRegularFile(yml)) {
                String text = Files.readString(yml);
                String marker = "# BEAT-AUTOTLS-BEGIN";
                if (text.contains(marker)) {
                    int a = text.indexOf(marker);
                    int b = text.indexOf("# BEAT-AUTOTLS-END");
                    if (b > a) {
                        text = (text.substring(0, a)
                                        + text.substring(b + "# BEAT-AUTOTLS-END".length()))
                                .replaceAll("\n{3,}", "\n\n")
                                .trim()
                                + "\n";
                        Files.writeString(yml, text);
                    }
                }
            }

            r.put("ok", true);
            r.put("hostname", serverHost);
            r.put("home", home.toString());
            r.put("keyStore", serverP12.toString());
            r.put("httpDisabled", true);
            r.put("httpPort", null);
            r.put("httpsPort", httpsPort);
            r.put("httpUrl", null);
            r.put("httpsUrl", "https://10.1.0.191:" + httpsPort + "/ui/");
            r.put("url", "https://10.1.0.191:" + httpsPort + "/ui/");
            r.put("note", "Plain HTTP http://10.1.0.191:8080 is disabled while AutoTLS is on");
            if (scheduleRestartImmediately) {
                scheduleManagerRestart();
                r.put("restart", "scheduled");
            } else {
                r.put("restart", "deferred");
            }
        } catch (Exception e) {
            r.put("ok", false);
            r.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return r;
    }

    private static Path managerHome() {
        Path beat = Path.of("/opt/beat-manager");
        if (Files.isDirectory(beat.resolve("bin"))) {
            return beat;
        }
        Path legacy = Path.of("/opt/bigtop-manager-server");
        if (Files.isDirectory(legacy.resolve("bin"))) {
            return legacy;
        }
        return beat;
    }

    private static void scheduleManagerRestart() {
        Path home = managerHome();
        // Fully detach: stop kills this JVM, so the restart must not be a child of it.
        String cmd = "nohup bash -c 'sleep 12; cd \""
                + home
                + "\" && ./bin/server.sh stop; sleep 3; ./bin/server.sh start' "
                + ">>/tmp/beat-autotls-restart.log 2>&1 &";
        try {
            new ProcessBuilder("bash", "-c", cmd).directory(home.toFile()).start();
            log.info("Scheduled detached Manager restart in ~15s (log: /tmp/beat-autotls-restart.log)");
        } catch (Exception e) {
            log.warn("Unable to schedule Manager restart: {}", e.getMessage());
        }
    }

    private Map<String, Object> enableServiceTls(String agentDir) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> updated = new ArrayList<>();
        List<Long> clusterIds = new ArrayList<>();
        try {
            List<HostPO> hosts = hostDao.findAll();
            if (hosts != null) {
                for (HostPO h : hosts) {
                    if (h.getClusterId() != null && !clusterIds.contains(h.getClusterId())) {
                        clusterIds.add(h.getClusterId());
                    }
                }
            }
            if (clusterIds.isEmpty()) {
                clusterIds.add(1L);
            }
            for (Long clusterId : clusterIds) {
                ServicePO hadoop = serviceDao.findByClusterIdAndName(clusterId, "hadoop");
                if (hadoop == null) {
                    continue;
                }
                patchConfigFile(
                        hadoop.getId(),
                        "ssl-server",
                        Map.of(
                                "ssl.server.keystore.location", agentDir + "/keystore.jks",
                                "ssl.server.keystore.password", STORE_PASS,
                                "ssl.server.keystore.keypassword", STORE_PASS,
                                "ssl.server.keystore.type", "jks",
                                "ssl.server.truststore.location", agentDir + "/truststore.jks",
                                "ssl.server.truststore.password", STORE_PASS,
                                "ssl.server.truststore.type", "jks"));
                patchConfigFile(
                        hadoop.getId(),
                        "ssl-client",
                        Map.of(
                                "ssl.client.keystore.location", agentDir + "/keystore.jks",
                                "ssl.client.keystore.password", STORE_PASS,
                                "ssl.client.keystore.type", "jks",
                                "ssl.client.truststore.location", agentDir + "/truststore.jks",
                                "ssl.client.truststore.password", STORE_PASS,
                                "ssl.client.truststore.type", "jks"));
                patchConfigFile(hadoop.getId(), "hdfs-site", Map.of("dfs.http.policy", "HTTP_AND_HTTPS"));
                hadoop.setRestartFlag(true);
                serviceDao.partialUpdateById(hadoop);
                updated.add("hadoop@" + clusterId);

                try {
                    CommandDTO cmd = new CommandDTO();
                    cmd.setCommand(Command.CONFIGURE);
                    cmd.setCommandLevel(CommandLevel.SERVICE);
                    cmd.setClusterId(clusterId);
                    ServiceCommandDTO sc = new ServiceCommandDTO();
                    sc.setServiceName("hadoop");
                    sc.setInstalled(true);
                    cmd.setServiceCommands(List.of(sc));
                    commandService.command(cmd);
                    r.put("configureJob", "started for hadoop cluster " + clusterId);
                } catch (Exception e) {
                    r.put("configureJob", "failed: " + e.getMessage());
                }
            }
            r.put("ok", !updated.isEmpty());
            r.put("updated", updated);
            r.put("policy", "HTTP_AND_HTTPS");
        } catch (Exception e) {
            r.put("ok", false);
            r.put("message", e.getMessage());
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private void patchConfigFile(Long serviceId, String fileName, Map<String, String> values) {
        List<ServiceConfigPO> configs = serviceConfigDao.findByServiceId(serviceId);
        if (configs == null) {
            return;
        }
        for (ServiceConfigPO cfg : configs) {
            if (!fileName.equals(cfg.getName())) {
                continue;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            Object raw = JsonUtils.readFromString(cfg.getPropertiesJson(), Object.class);
            if (raw instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        m.forEach((k, v) -> row.put(String.valueOf(k), v));
                        list.add(row);
                    }
                }
            }
            for (Map.Entry<String, String> e : values.entrySet()) {
                boolean found = false;
                for (Map<String, Object> row : list) {
                    if (e.getKey().equals(String.valueOf(row.get("name")))) {
                        row.put("value", e.getValue());
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.getKey());
                    row.put("value", e.getValue());
                    list.add(row);
                }
            }
            cfg.setPropertiesJson(JsonUtils.writeAsString(list));
            serviceConfigDao.partialUpdateById(cfg);
            return;
        }
    }

    private static void buildTruststore(Path caCrt, Path outP12) throws Exception {
        Files.deleteIfExists(outP12);
        Path keytool = resolveKeytool();
        try {
            exec(
                    keytool.toString(),
                    "-importcert",
                    "-noprompt",
                    "-alias",
                    "beat-ca",
                    "-file",
                    caCrt.toString(),
                    "-keystore",
                    outP12.toString(),
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    STORE_PASS);
            return;
        } catch (Exception keytoolEx) {
            log.warn("keytool truststore failed ({}), falling back to openssl", keytoolEx.getMessage());
            Files.deleteIfExists(outP12);
            exec(
                    resolveOpenssl().toString(),
                    "pkcs12",
                    "-export",
                    "-nokeys",
                    "-in",
                    caCrt.toString(),
                    "-out",
                    outP12.toString(),
                    "-name",
                    "beat-ca",
                    "-password",
                    "pass:" + STORE_PASS);
        }
    }

    private static void buildHostPkcs12(Path crt, Path key, Path ca, Path outP12) throws Exception {
        Files.deleteIfExists(outP12);
        exec(
                resolveOpenssl().toString(),
                "pkcs12",
                "-export",
                "-in",
                crt.toString(),
                "-inkey",
                key.toString(),
                "-certfile",
                ca.toString(),
                "-out",
                outP12.toString(),
                "-name",
                "beat",
                "-password",
                "pass:" + STORE_PASS);
    }

    /** keytool is often missing from PATH even when the JVM is running — resolve via java.home. */
    private static Path resolveKeytool() {
        Path fromJavaHome = Path.of(System.getProperty("java.home", ""), "bin", "keytool");
        if (Files.isExecutable(fromJavaHome) || Files.isRegularFile(fromJavaHome)) {
            return fromJavaHome;
        }
        Path sibling = Path.of(System.getProperty("java.home", "")).getParent();
        if (sibling != null) {
            Path jdkBin = sibling.resolve("bin").resolve("keytool");
            if (Files.isRegularFile(jdkBin)) {
                return jdkBin;
            }
        }
        return Path.of("keytool");
    }

    private static Path resolveOpenssl() {
        Path[] candidates = {
            Path.of("/usr/bin/openssl"),
            Path.of("/bin/openssl"),
            Path.of("openssl")
        };
        for (Path p : candidates) {
            if ("openssl".equals(p.toString()) || Files.isRegularFile(p)) {
                return p;
            }
        }
        return Path.of("/usr/bin/openssl");
    }

    private void issueHostCerts() {
        Path hostsDir = AlephysStore.TLS_DIR.resolve("hosts");
        try {
            Files.createDirectories(hostsDir);
            Path caCrt = AlephysStore.TLS_DIR.resolve("ca.crt");
            Path caKey = AlephysStore.TLS_DIR.resolve("ca.key");
            List<HostPO> hosts = hostDao.findAll();
            List<String> names = new ArrayList<>();
            if (hosts != null) {
                for (HostPO h : hosts) {
                    if (h.getHostname() != null && !h.getHostname().isBlank()) {
                        names.add(h.getHostname().trim());
                    }
                }
            }
            if (names.isEmpty()) {
                names.add("ravitejacdp1.infra.alephys.com");
                names.add("ravitejacdp2.infra.alephys.com");
                names.add("ravitejacdp3.infra.alephys.com");
            }
            for (String cn : names) {
                Path key = hostsDir.resolve(cn + ".key");
                Path csr = hostsDir.resolve(cn + ".csr");
                Path crt = hostsDir.resolve(cn + ".crt");
                if (Files.isRegularFile(crt) && Files.isRegularFile(key)) {
                    continue;
                }
                exec(resolveOpenssl().toString(), "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=" + cn + "/O=BEAT",
                        "-keyout", key.toString(), "-out", csr.toString());
                exec(resolveOpenssl().toString(), "x509", "-req", "-in", csr.toString(), "-CA", caCrt.toString(), "-CAkey",
                        caKey.toString(), "-CAcreateserial", "-days", "365", "-out", crt.toString());
            }
        } catch (Exception ignored) {
            // status reports what exists
        }
    }

    private static Map<String, Object> defaultTargets() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("agents", true);
        t.put("managerServer", true);
        t.put("managerUi", true);
        t.put("agentGrpc", true);
        t.put("services", true);
        return t;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("command timed out: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            String detail = new String(out).trim();
            if (detail.length() > 400) {
                detail = detail.substring(detail.length() - 400);
            }
            throw new IllegalStateException(
                    "command failed (" + p.exitValue() + "): " + String.join(" ", cmd)
                            + (detail.isEmpty() ? "" : " — " + detail));
        }
    }
}
