/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.bigtop.manager.server.service.advisory;

import org.apache.bigtop.manager.common.utils.JsonUtils;
import org.apache.bigtop.manager.dao.po.ComponentPO;
import org.apache.bigtop.manager.dao.po.HostPO;
import org.apache.bigtop.manager.dao.po.ServiceConfigPO;
import org.apache.bigtop.manager.dao.po.ServicePO;
import org.apache.bigtop.manager.server.model.dto.PropertyDTO;
import org.apache.bigtop.manager.server.model.vo.AdvisorySuggestionVO;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rule-based Pulse-lite detectors. Advise only — never mutate the cluster.
 */
public final class AdvisoryDetector {

    static final int DISK_USED_PCT_WARN = 80;
    static final int MEM_USED_PCT_WARN = 90;

    private AdvisoryDetector() {}

    public static AdvisorySuggestionVO card(
            String id,
            String severity,
            String service,
            String mode,
            String problem,
            String why,
            String fix,
            String verify) {
        AdvisorySuggestionVO vo = new AdvisorySuggestionVO();
        vo.setId(id);
        vo.setSeverity(severity);
        vo.setService(service);
        vo.setMode(mode);
        vo.setProblem(problem);
        vo.setWhyItMatters(why);
        vo.setSuggestedFix(fix);
        vo.setHowToVerify(verify);
        vo.setConfidence("High");
        vo.setAdvisoryOnly(true);
        return vo;
    }

    public static List<AdvisorySuggestionVO> hostResourceCards(List<HostPO> hosts) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (hosts == null) {
            return list;
        }
        for (HostPO host : hosts) {
            String name = host.getHostname() != null ? host.getHostname() : ("host-" + host.getId());
            Integer diskPct = usedPercent(host.getFreeDisk(), host.getTotalDisk());
            if (diskPct != null && diskPct >= DISK_USED_PCT_WARN) {
                list.add(card(
                        "host-disk-" + host.getId(),
                        diskPct >= 90 ? "Critical" : "High",
                        "Host",
                        "capacity",
                        "Host " + name + " disk is " + diskPct + "% full",
                        "HDFS, ZooKeeper snapshots, and logs will fail when the disk fills. Small-file and DN problems follow.",
                        "1) Open Cluster → Hosts and inspect " + name + ".\n"
                                + "2) Clear old logs under /var/log and unused tarballs in the BEAT parcel repo if safe.\n"
                                + "3) Move or compact HDFS data dirs if this node is a DataNode.\n"
                                + "4) Do not auto-delete data — a human decides.",
                        "Disk used percent drops below " + DISK_USED_PCT_WARN + "% on the Hosts page."));
            }
            Integer memPct = usedPercent(host.getFreeMemorySize(), host.getTotalMemorySize());
            if (memPct != null && memPct >= MEM_USED_PCT_WARN) {
                list.add(card(
                        "host-mem-" + host.getId(),
                        "Medium",
                        "Host",
                        "capacity",
                        "Host " + name + " memory is " + memPct + "% used",
                        "NameNode, YARN, and ZK will swap or OOM if heap + OS cache has no room.",
                        "1) Check running JVMs on " + name + ".\n"
                                + "2) Lower a service heap in Configs (then restart that service from the UI).\n"
                                + "3) Do not start more roles on this host until memory recovers.",
                        "Memory used percent drops below " + MEM_USED_PCT_WARN + "%."));
            }
        }
        return list;
    }

    /**
     * Stale-config cards: prefer concrete property diffs / heuristic recommendations.
     * Generic "open Configs" steps are fallback only when no property-level advice exists.
     */
    public static List<AdvisorySuggestionVO> staleRestartCards(List<ServicePO> services) {
        return staleRestartCards(services, Map.of(), Map.of(), Map.of());
    }

    /**
     * @param configsByService configs for each service id
     * @param latestSnapshotDescByService latest history snapshot {@code desc} JSON (has changes[])
     * @param dataNodeCountByService DataNode counts for HDFS replication checks
     */
    public static List<AdvisorySuggestionVO> staleRestartCards(
            List<ServicePO> services,
            Map<Long, List<ServiceConfigPO>> configsByService,
            Map<Long, String> latestSnapshotDescByService,
            Map<Long, Integer> dataNodeCountByService) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (services == null) {
            return list;
        }
        for (ServicePO svc : services) {
            if (!Boolean.TRUE.equals(svc.getRestartFlag())) {
                continue;
            }
            Long sid = svc.getId();
            List<ServiceConfigPO> configs =
                    configsByService != null ? configsByService.getOrDefault(sid, List.of()) : List.of();
            String snapDesc =
                    latestSnapshotDescByService != null ? latestSnapshotDescByService.get(sid) : null;
            int dn = dataNodeCountByService != null ? dataNodeCountByService.getOrDefault(sid, 0) : 0;
            list.add(staleRestartCard(svc, configs, snapDesc, dn));
        }
        return list;
    }

    public static AdvisorySuggestionVO staleRestartCard(
            ServicePO svc, List<ServiceConfigPO> configs, String latestSnapshotDesc, int dataNodeCount) {
        String svcName = display(svc);
        String fix = buildConcreteConfigFix(svc, configs, latestSnapshotDesc, dataNodeCount);
        return card(
                "stale-config-" + svc.getId(),
                "Medium",
                svcName,
                "stale-config",
                svcName + " has stale configs (restart required)",
                "Saved configs are not live until roles are restarted. The cluster is still running the previous values.",
                fix,
                "Service overview shows Restart required = No.");
    }

    /** Prefer history diffs, then heuristic recommendations, else generic UI steps. */
    static String buildConcreteConfigFix(
            ServicePO svc, List<ServiceConfigPO> configs, String latestSnapshotDesc, int dataNodeCount) {
        List<String> changeLines = formatSnapshotChanges(latestSnapshotDesc);
        if (!changeLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("CONFIG CHANGES (saved - restart to apply):\n");
            for (String line : changeLines) {
                sb.append(line).append('\n');
            }
            sb.append("\nThen in BEAT:\n");
            sb.append("1) Confirm the values above under Configs.\n");
            sb.append("2) Restart ").append(display(svc)).append(" so roles load them.\n");
            sb.append("3) Confirm the restart badge clears.");
            return sb.toString().trim();
        }

        List<AdvisorySuggestionVO> reviews = configReviewCards(svc, configs, dataNodeCount);
        if (!reviews.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("RECOMMENDED CONFIG CHANGES:\n");
            int i = 1;
            for (AdvisorySuggestionVO r : reviews) {
                String oneLiner = summarizeConfigReviewFix(r.getSuggestedFix(), r.getProblem());
                sb.append(i++).append(") ").append(oneLiner).append('\n');
            }
            sb.append("\nThen in BEAT: Save/Apply Config → Restart ").append(display(svc)).append('.');
            return sb.toString().trim();
        }

        // Fallback when we have no property-level signal
        return "No specific property diff was recorded for this save.\n"
                + "1) Open "
                + display(svc)
                + " → Configs and compare recent History (old → new values).\n"
                + "2) Set the intended property values, Save/Apply Config.\n"
                + "3) Restart the service from BEAT.\n"
                + "4) Confirm the restart badge clears.";
    }

    static List<String> formatSnapshotChanges(String snapshotDescJson) {
        List<String> lines = new ArrayList<>();
        if (snapshotDescJson == null || snapshotDescJson.isBlank()) {
            return lines;
        }
        try {
            Map<String, Object> meta =
                    JsonUtils.readFromString(snapshotDescJson, new TypeReference<Map<String, Object>>() {});
            if (meta == null) {
                return lines;
            }
            Object raw = meta.get("changes");
            if (!(raw instanceof List<?> changes)) {
                return lines;
            }
            int n = 0;
            for (Object item : changes) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                Object fileObj = row.get("file");
                Object propObj = row.get("property");
                Object oldObj = row.get("oldValue");
                Object newObj = row.get("newValue");
                String file = fileObj == null ? "?" : String.valueOf(fileObj);
                String property = propObj == null ? "?" : String.valueOf(propObj);
                String oldValue = oldObj == null ? "" : String.valueOf(oldObj);
                String newValue = newObj == null ? "" : String.valueOf(newObj);
                if (looksSecret(property)) {
                    oldValue = "[REDACTED]";
                    newValue = "[REDACTED]";
                }
                lines.add("- [" + file + "] " + property + ": " + oldValue + " → " + newValue);
                if (++n >= 12) {
                    lines.add("- … (" + (changes.size() - n) + " more)");
                    break;
                }
            }
        } catch (Exception ignored) {
            // malformed history — ignore
        }
        return lines;
    }

    static String summarizeConfigReviewFix(String suggestedFix, String problem) {
        if (suggestedFix != null) {
            // Prefer lines that look like "Set X to Y"
            for (String line : suggestedFix.split("\n")) {
                String t = line.replaceFirst("^\\d+\\)\\s*", "").trim();
                if (t.toLowerCase(Locale.ROOT).startsWith("set ")
                        || t.contains(" → ")
                        || t.toLowerCase(Locale.ROOT).contains("set to")
                        || t.toLowerCase(Locale.ROOT).contains("set it to")) {
                    return t;
                }
            }
        }
        return problem != null ? problem : "Review Configs and set recommended values.";
    }

    static boolean looksSecret(String property) {
        if (property == null) {
            return false;
        }
        String p = property.toLowerCase(Locale.ROOT);
        return p.contains("password")
                || p.contains("passwd")
                || p.contains("secret")
                || p.contains("token")
                || p.contains("keytab")
                || p.contains("private");
    }

    /** Compact redacted config dump for LLM RCA (skip secrets). */
    public static String formatConfigsForLlm(List<ServiceConfigPO> configs, int maxChars) {
        if (configs == null || configs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ServiceConfigPO cfg : configs) {
            Map<String, String> props = propertiesOf(cfg);
            if (props.isEmpty()) {
                continue;
            }
            sb.append("FILE ").append(cfg.getName()).append(":\n");
            int n = 0;
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (looksSecret(e.getKey())) {
                    sb.append("  ").append(e.getKey()).append("=[REDACTED]\n");
                } else {
                    String v = e.getValue() == null ? "" : e.getValue();
                    if (v.length() > 120) {
                        v = v.substring(0, 117) + "...";
                    }
                    sb.append("  ").append(e.getKey()).append("=").append(v).append('\n');
                }
                if (++n >= 40) {
                    sb.append("  …\n");
                    break;
                }
            }
            if (sb.length() >= maxChars) {
                break;
            }
        }
        String out = sb.toString();
        if (out.length() > maxChars) {
            return out.substring(0, maxChars) + "\n…";
        }
        return out;
    }

    public static List<AdvisorySuggestionVO> componentUnhealthyCards(List<ComponentPO> components) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (components == null) {
            return list;
        }
        for (ComponentPO comp : components) {
            String n = comp.getName() != null ? comp.getName().toLowerCase(Locale.ROOT) : "";
            if (n.endsWith("_client") || "zkfc".equals(n) || "journalnode".equals(n)) {
                continue;
            }
            String svc = comp.getServiceDisplayName() != null && !comp.getServiceDisplayName().isBlank()
                    ? comp.getServiceDisplayName()
                    : (comp.getServiceName() != null ? comp.getServiceName() : "Service");
            String cname = comp.getDisplayName() != null ? comp.getDisplayName() : comp.getName();
            String host = comp.getHostname() != null ? comp.getHostname() : ("host-" + comp.getHostId());
            list.add(card(
                    "component-unhealthy-" + comp.getId(),
                    "High",
                    svc,
                    "alert",
                    cname + " on " + host + " is unhealthy",
                    "A down role breaks quorum, HDFS writes, or YARN scheduling depending on the component.",
                    "1) Open the service → Components and inspect " + cname + " on " + host + ".\n"
                            + "2) Read the role log on that host.\n"
                            + "3) Start or restart only that component from the UI after a human reviews the cause.",
                    "Component status returns to healthy."));
        }
        return list;
    }

    public static List<AdvisorySuggestionVO> hdfsSafetyCards(String nnHttp) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (nnHttp == null || nnHttp.isBlank()) {
            return list;
        }
        try {
            String url = nnHttp.endsWith("/") ? nnHttp : nnHttp + "/";
            String body = httpGet(url + "jmx?qry=Hadoop:service=NameNode,name=FSNamesystemState");
            if (body == null || body.isBlank()) {
                return list;
            }
            Long under = jsonLong(body, "UnderReplicatedBlocks");
            Long missing = jsonLong(body, "MissingBlocks");
            Long files = jsonLong(body, "FilesTotal");
            Long blocks = jsonLong(body, "BlocksTotal");
            Long capacity = jsonLong(body, "CapacityTotal");
            if (missing != null && missing > 0) {
                list.add(card(
                        "hdfs-missing-blocks",
                        "High",
                        "Hadoop",
                        "alert",
                        missing + " missing HDFS block(s)",
                        "Missing blocks mean data is gone from every replica. Workloads reading those files will fail.",
                        "1) Run fsck from a Hadoop client and list the affected paths.\n"
                                + "2) Restore from backup or drop the corrupt files after a human decides.\n"
                                + "3) AI will not delete or re-replicate automatically.",
                        "NameNode JMX MissingBlocks returns to 0."));
            }
            if (under != null && under > 0) {
                list.add(card(
                        "hdfs-under-replicated",
                        "High",
                        "Hadoop",
                        "alert",
                        under + " under-replicated HDFS block(s)",
                        "HDFS cannot place the configured replica count. Disk, DN down, or dfs.replication too high.",
                        "1) Check DataNode health in BM.\n"
                                + "2) Confirm dfs.replication <= live DN count.\n"
                                + "3) Leave the NN to re-replicate, or start missing DNs from the UI.",
                        "UnderReplicatedBlocks returns to 0 on NN JMX."));
            }
            if (files != null && files > 20000 && capacity != null && capacity > 0) {
                double avg = capacity * 1.0 / files;
                if (avg < 1_000_000d) {
                    list.add(card(
                            "hdfs-small-files",
                            "Medium",
                            "Hadoop",
                            "alert",
                            "NameNode is tracking " + files + " files with small average size",
                            "Tiny files burn NameNode heap. Iceberg/ORC compaction or fewer ingest files fixes this.",
                            "1) Inspect /warehouse ingest paths.\n"
                                    + "2) Compact Iceberg snapshots / ORC files.\n"
                                    + "3) Change the producer — do not auto-delete from AI.",
                            "FilesTotal drops or average file size rises after compaction."));
                }
            }
            if (blocks != null && files != null && files > 0) {
                // keep compiler happy — blocks used for future ratio cards
            }
        } catch (Exception ignored) {
            // NN down already produces UNHEALTHY cards
        }
        return list;
    }

    public static List<AdvisorySuggestionVO> tlsExpiryCards(String notAfterRaw) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (notAfterRaw == null || notAfterRaw.isBlank()) {
            return list;
        }
        long days = daysUntilOpensslDate(notAfterRaw);
        if (days >= 0 && days <= 30) {
            list.add(card(
                    "tls-ca-expiry",
                    days <= 7 ? "High" : "Medium",
                    "Security",
                    "alert",
                    "BEAT lab CA expires in " + days + " day(s)",
                    "AutoTLS-lite CA is close to expiry. Agents and future TLS services will fail trust.",
                    "1) Open Estates → TLS and re-issue the lab CA.\n"
                            + "2) Redistribute ca.crt to hosts.\n"
                            + "3) Do not enable Kerberos from this card.",
                    "ca.crt notAfter is more than 30 days away."));
        }
        return list;
    }

    public static List<AdvisorySuggestionVO> tenantQuotaCards(List<Map<String, Object>> estates) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (estates == null) {
            return list;
        }
        for (Map<String, Object> e : estates) {
            if (e == null) {
                continue;
            }
            String name = String.valueOf(e.getOrDefault("name", "tenant"));
            Object usedPct = e.get("usedPercent");
            if (usedPct instanceof Number && ((Number) usedPct).doubleValue() >= 80d) {
                list.add(card(
                        "tenant-quota-" + name,
                        "Medium",
                        "Lakehouse",
                        "alert",
                        "Tenant " + name + " is at " + usedPct + "% of HDFS quota",
                        "Chargeback lite: this estate is about to fail writes. Raise quota or compact data.",
                        "1) Open Estates and check " + name + " path/quota.\n"
                                + "2) hdfs dfsadmin -setSpaceQuota after a human decides.\n"
                                + "3) AI will not change quotas.",
                        "usedPercent drops below 80 after cleanup or quota raise."));
            }
        }
        return list;
    }

    static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent", "BEAT/advisory");
        if (c.getResponseCode() != 200) {
            c.disconnect();
            return null;
        }
        byte[] buf = c.getInputStream().readAllBytes();
        c.disconnect();
        return new String(buf);
    }

    static Long jsonLong(String body, String key) {
        try {
            String needle = "\"" + key + "\"";
            int i = body.indexOf(needle);
            if (i < 0) {
                return null;
            }
            int colon = body.indexOf(':', i);
            int j = colon + 1;
            while (j < body.length() && (body.charAt(j) == ' ' || body.charAt(j) == '\n')) {
                j++;
            }
            int k = j;
            while (k < body.length() && (Character.isDigit(body.charAt(k)) || body.charAt(k) == '-')) {
                k++;
            }
            if (k == j) {
                return null;
            }
            return Long.parseLong(body.substring(j, k));
        } catch (Exception e) {
            return null;
        }
    }

    static long daysUntilOpensslDate(String raw) {
        try {
            java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy z", java.util.Locale.ENGLISH);
            java.time.ZonedDateTime z = java.time.ZonedDateTime.parse(raw.trim(), fmt);
            return java.time.Duration.between(java.time.Instant.now(), z.toInstant()).toDays();
        } catch (Exception e) {
            return -1;
        }
    }

    public static List<AdvisorySuggestionVO> configReviewCards(
            ServicePO service, List<ServiceConfigPO> configs, int dataNodeCount) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (service == null) {
            return list;
        }
        String svcName = display(service);
        Map<String, Map<String, String>> byFile = configMaps(configs);
        String name = service.getName() != null ? service.getName().toLowerCase(Locale.ROOT) : "";

        if ("zookeeper".equals(name)) {
            list.addAll(zookeeperConfigCards(svcName, service.getId(), byFile.getOrDefault("zoo.cfg", Map.of())));
        }
        if ("hadoop".equals(name)) {
            list.addAll(hadoopConfigCards(
                    svcName, service.getId(), byFile.getOrDefault("hdfs-site", Map.of()), dataNodeCount));
        }
        return list;
    }

    static List<AdvisorySuggestionVO> zookeeperConfigCards(
            String svcName, Long serviceId, Map<String, String> zoo) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        String maxCnxns = zoo.get("maxClientCnxns");
        if (maxCnxns == null || maxCnxns.isBlank() || "0".equals(maxCnxns.trim())) {
            list.add(card(
                    "zk-maxclient-" + serviceId,
                    "Medium",
                    svcName,
                    "config-review",
                    "ZooKeeper maxClientCnxns is unlimited or unset",
                    "A single noisy client can exhaust file descriptors and take down the quorum.",
                    "1) Open ZooKeeper → Configs → zoo.cfg.\n"
                            + "2) Set maxClientCnxns to 60 (or another positive limit).\n"
                            + "3) Save, then restart ZooKeeper from the UI when ready.",
                    "maxClientCnxns is a positive integer and the restart badge is clear."));
        }
        String whitelist = zoo.getOrDefault("4lw.commands.whitelist", "");
        Set<String> words = Set.of(whitelist.toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
        if (!words.contains("srvr") || !words.contains("mntr")) {
            list.add(card(
                    "zk-4lw-" + serviceId,
                    "Low",
                    svcName,
                    "config-review",
                    "ZooKeeper 4lw whitelist is too tight for ops",
                    "Without srvr and mntr, zkServer.sh and metrics/detectors cannot inspect the quorum.",
                    "1) Open zoo.cfg → 4lw.commands.whitelist.\n"
                            + "2) Set to ruok,srvr,mntr,conf.\n"
                            + "3) Save and restart ZooKeeper from the UI.",
                    "echo mntr | nc <zk-host> 2181 returns metrics."));
        }
        String purge = zoo.get("autopurge.purgeInterval");
        if (purge != null && "0".equals(purge.trim())) {
            list.add(card(
                    "zk-purge-" + serviceId,
                    "Medium",
                    svcName,
                    "config-review",
                    "ZooKeeper snapshot autopurge is disabled",
                    "dataDir will grow until the disk fills and the quorum dies.",
                    "1) Set autopurge.purgeInterval to 24 and keep snapRetainCount around 30.\n"
                            + "2) Save and restart ZooKeeper.",
                    "autopurge.purgeInterval is >= 1 in Configs."));
        }
        Integer tick = parseInt(zoo.get("tickTime"));
        if (tick != null && tick > 5000) {
            list.add(card(
                    "zk-tick-" + serviceId,
                    "Low",
                    svcName,
                    "config-review",
                    "ZooKeeper tickTime is " + tick + " ms (slow)",
                    "Session timeouts and failover stretch with a large tick. 2000–3000 ms is the usual range.",
                    "1) Set tickTime to 3000 unless you have a measured reason not to.\n"
                            + "2) Save and restart ZooKeeper.",
                    "tickTime is 2000–3000 after restart."));
        }
        String adminPort = zoo.get("admin.serverPort");
        if ("8080".equals(adminPort)) {
            list.add(card(
                    "zk-admin-port-" + serviceId,
                    "High",
                    svcName,
                    "config-review",
                    "ZooKeeper AdminServer port 8080 collides with BEAT",
                    "Both will fight for 8080 on cdp1. BEAT UI or ZK admin will fail to bind.",
                    "1) Set admin.serverPort to 9393 (or disable admin.enableServer).\n"
                            + "2) Save and restart ZooKeeper.",
                    "ss -lntp shows BEAT on 8080 and ZK admin on 9393."));
        }
        return list;
    }

    static List<AdvisorySuggestionVO> hadoopConfigCards(
            String svcName, Long serviceId, Map<String, String> hdfsSite, int dataNodeCount) {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        Integer replication = parseInt(hdfsSite.get("dfs.replication"));
        if (dataNodeCount > 0 && replication != null && replication > dataNodeCount) {
            list.add(card(
                    "hdfs-repl-" + serviceId,
                    "High",
                    svcName,
                    "config-review",
                    "dfs.replication is " + replication + " but only " + dataNodeCount + " DataNode(s)",
                    "HDFS cannot place enough replicas. Writes hang in under-replicated / pipeline setup.",
                    "1) Open Hadoop → Configs → hdfs-site → dfs.replication.\n"
                            + "2) Set it to " + Math.max(1, Math.min(3, dataNodeCount)) + " (not more than DataNode count).\n"
                            + "3) Save and restart NameNode / DataNodes from the UI.",
                    "dfs.replication <= live DataNode count and fsck shows healthy blocks."));
        }
        return list;
    }

    static Integer usedPercent(Long free, Long total) {
        if (free == null || total == null || total <= 0) {
            return null;
        }
        long used = total - free;
        if (used < 0) {
            used = 0;
        }
        return (int) Math.round(used * 100.0 / total);
    }

    static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Map<String, Map<String, String>> configMaps(List<ServiceConfigPO> configs) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (configs == null) {
            return out;
        }
        for (ServiceConfigPO cfg : configs) {
            out.put(cfg.getName(), propertiesOf(cfg));
        }
        return out;
    }

    static Map<String, String> propertiesOf(ServiceConfigPO cfg) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cfg == null || cfg.getPropertiesJson() == null || cfg.getPropertiesJson().isBlank()) {
            return map;
        }
        try {
            List<PropertyDTO> props =
                    JsonUtils.readFromString(cfg.getPropertiesJson(), new TypeReference<List<PropertyDTO>>() {});
            if (props == null) {
                return map;
            }
            for (PropertyDTO p : props) {
                if (p.getName() != null) {
                    map.put(p.getName(), p.getValue());
                }
            }
        } catch (Exception ignored) {
            // malformed json — skip this file
        }
        return map;
    }

    private static String display(ServicePO svc) {
        if (svc.getDisplayName() != null && !svc.getDisplayName().isBlank()) {
            return svc.getDisplayName();
        }
        return svc.getName();
    }
}
