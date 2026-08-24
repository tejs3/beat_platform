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

import org.apache.bigtop.manager.dao.po.HostPO;
import org.apache.bigtop.manager.dao.repository.HostDao;

import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Advise-only evidence: BEAT Server asks hosts (via SSH used by agents) for recent logs.
 * Never starts/stops/edits the cluster.
 */
@Service
public class AlephysEvidenceService {

    @Resource
    private HostDao hostDao;

    public Map<String, Object> fetchLogs(Map<String, Object> body) {
        String service = body == null ? "" : String.valueOf(body.getOrDefault("service", "")).trim().toLowerCase(Locale.ROOT);
        int lines = 120;
        try {
            if (body != null && body.get("lines") != null) {
                lines = Math.min(400, Math.max(20, Integer.parseInt(String.valueOf(body.get("lines")))));
            }
        } catch (Exception ignored) {
            lines = 120;
        }
        if (service.isEmpty()) {
            service = "hadoop";
        }
        String glob = logGlob(service);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("advisoryOnly", true);
        out.put("service", service);
        out.put("note", "Evidence only — BEAT AI / operator reads logs; no auto-fix.");
        List<Map<String, Object>> hosts = new ArrayList<>();
        List<HostPO> all = hostDao.findAll();
        if (all != null) {
            for (HostPO h : all) {
                if (h.getHostname() == null) {
                    continue;
                }
                String hostnameFilter =
                        body == null ? "" : String.valueOf(body.getOrDefault("hostname", "")).trim();
                if (!hostnameFilter.isEmpty()) {
                    String hn = h.getHostname();
                    String ip = h.getIpv4() == null ? "" : h.getIpv4();
                    if (!hn.equalsIgnoreCase(hostnameFilter)
                            && !ip.equals(hostnameFilter)
                            && !hn.toLowerCase(Locale.ROOT).contains(hostnameFilter.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("hostname", h.getHostname());
                row.put("ipv4", h.getIpv4());
                row.put("agentStatus", h.getStatus());
                row.put("agentHeartbeat", h.getUpdateTime() == null ? null : String.valueOf(h.getUpdateTime()));
                try {
                    String cmd =
                            "bash -lc " + shellQuote("ls -1t " + glob + " 2>/dev/null | head -3 | while read f; do "
                                    + "echo \"===== $f =====\"; tail -n "
                                    + lines
                                    + " \"$f\" 2>/dev/null | tail -n "
                                    + lines
                                    + "; done");
                    String target = h.getIpv4() != null && !h.getIpv4().isBlank() ? h.getIpv4() : h.getHostname();
                    Process p = new ProcessBuilder(
                                    "ssh",
                                    "-o",
                                    "StrictHostKeyChecking=no",
                                    "-o",
                                    "ConnectTimeout=4",
                                    "root@" + target,
                                    cmd)
                            .redirectErrorStream(true)
                            .start();
                    boolean finished = p.waitFor(20, TimeUnit.SECONDS);
                    String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (!finished) {
                        p.destroyForcibly();
                        row.put("ok", false);
                        row.put("message", "timeout");
                    } else {
                        row.put("ok", p.exitValue() == 0 || !text.isBlank());
                        row.put("excerpt", text.length() > 24000 ? text.substring(0, 24000) + "\n...(truncated)" : text);
                    }
                } catch (Exception e) {
                    row.put("ok", false);
                    row.put("message", e.getMessage());
                }
                hosts.add(row);
            }
        }
        out.put("hosts", hosts);
        return out;
    }

    private static String logGlob(String service) {
        return switch (service) {
            case "hbase" -> "/var/log/hbase/*.log";
            case "hive" -> "/var/log/hive/*.log /var/log/hive/*.out";
            case "spark" -> "/var/log/spark/*.log /opt/services/spark/logs/*.log";
            case "zookeeper", "zk" -> "/var/log/zookeeper/*.log /opt/services/zookeeper/logs/*.out";
            case "yarn" -> "/var/log/hadoop-yarn/*.log /opt/services/hadoop/logs/*yarn*.log";
            case "prometheus" ->
                "/opt/services/prometheus/nohup.out /opt/services/prometheus/logs/*.log /var/log/prometheus/*.log /opt/services/prometheus*/nohup.out";
            case "hadoop", "hdfs" -> "/var/log/hadoop/*.log /opt/services/hadoop/logs/*.log";
            default -> "/var/log/"
                    + service
                    + "/*.log /opt/services/"
                    + service
                    + "/logs/*.log /opt/services/"
                    + service
                    + "/nohup.out /opt/services/"
                    + service
                    + "/*.out";
        };
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
