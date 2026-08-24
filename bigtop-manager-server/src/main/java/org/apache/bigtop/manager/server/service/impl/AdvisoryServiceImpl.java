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
package org.apache.bigtop.manager.server.service.impl;

import org.apache.bigtop.manager.dao.po.ClusterPO;
import org.apache.bigtop.manager.dao.po.ComponentPO;
import org.apache.bigtop.manager.dao.po.HostPO;
import org.apache.bigtop.manager.dao.po.ServiceConfigPO;
import org.apache.bigtop.manager.dao.po.ServiceConfigSnapshotPO;
import org.apache.bigtop.manager.dao.po.ServicePO;
import org.apache.bigtop.manager.dao.query.ComponentQuery;
import org.apache.bigtop.manager.dao.repository.ClusterDao;
import org.apache.bigtop.manager.dao.repository.ComponentDao;
import org.apache.bigtop.manager.dao.repository.HostDao;
import org.apache.bigtop.manager.dao.repository.ServiceConfigDao;
import org.apache.bigtop.manager.dao.repository.ServiceConfigSnapshotDao;
import org.apache.bigtop.manager.dao.repository.ServiceDao;
import org.apache.bigtop.manager.server.enums.ApiExceptionEnum;
import org.apache.bigtop.manager.server.enums.HealthyStatusEnum;
import org.apache.bigtop.manager.server.exception.ApiException;
import org.apache.bigtop.manager.server.model.vo.AdvisorySuggestionVO;
import org.apache.bigtop.manager.server.service.AdvisoryService;
import org.apache.bigtop.manager.server.service.advisory.AdvisoryDetector;
import org.apache.bigtop.manager.server.service.advisory.AdvisoryRcaService;
import org.apache.bigtop.manager.server.service.alephys.AlephysStore;

import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdvisoryServiceImpl implements AdvisoryService {

    @Resource
    private ClusterDao clusterDao;

    @Resource
    private HostDao hostDao;

    @Resource
    private ServiceDao serviceDao;

    @Resource
    private ServiceConfigDao serviceConfigDao;

    @Resource
    private ServiceConfigSnapshotDao serviceConfigSnapshotDao;

    @Resource
    private ComponentDao componentDao;

    @Resource
    private AlephysStore alephysStore;

    @Resource
    private AdvisoryRcaService advisoryRcaService;

    @Override
    public List<AdvisorySuggestionVO> listSuggestions() {
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        List<ClusterPO> clusters = clusterDao.findAll();
        if (clusters == null || clusters.isEmpty()) {
            return list;
        }

        List<HostPO> hosts = hostDao.findAll();
        if (hosts != null) {
            for (HostPO host : hosts) {
                if (!isUnhealthy(host.getStatus())) {
                    continue;
                }
                String name = host.getHostname() != null ? host.getHostname() : ("host-" + host.getId());
                String err = host.getErrInfo() != null && !host.getErrInfo().isBlank()
                        ? host.getErrInfo()
                        : "Agent reported unhealthy.";
                AdvisorySuggestionVO card = AdvisoryDetector.card(
                        "host-unhealthy-" + host.getId(),
                        "High",
                        "Host",
                        "alert",
                        "Host " + name + " is unhealthy",
                        err + " Unhealthy hosts break service ops and installs.",
                        "1) In BEAT → Hosts, open this node and check agent status.\n"
                                + "2) Confirm the agent process is running and can reach the server.\n"
                                + "3) Fix disk/network/agent, then refresh Hosts.\n"
                                + "4) Restart roles from BEAT only after the host is reachable.",
                        "Host status returns to healthy in BEAT → Hosts.");
                list.add(advisoryRcaService.enrichWithLogsAndLlm(card, "hadoop", name));
            }
            list.addAll(AdvisoryDetector.hostResourceCards(hosts));
        }

        List<ServicePO> services = serviceDao.findAll();
        if (services != null) {
            Map<Long, List<ServiceConfigPO>> configsByService = new HashMap<>();
            Map<Long, String> latestSnapByService = new HashMap<>();
            Map<Long, Integer> dnByService = new HashMap<>();
            for (ServicePO svc : services) {
                List<ServiceConfigPO> configs = serviceConfigDao.findByServiceId(svc.getId());
                configsByService.put(svc.getId(), configs != null ? configs : List.of());
                dnByService.put(svc.getId(), countDataNodes(svc.getId()));
                latestSnapByService.put(svc.getId(), latestSnapshotDesc(svc.getId()));
            }

            for (ServicePO svc : services) {
                if (isUnhealthy(svc.getStatus())) {
                    String svcName = svc.getDisplayName() != null && !svc.getDisplayName().isBlank()
                            ? svc.getDisplayName()
                            : svc.getName();
                    AdvisorySuggestionVO card = AdvisoryDetector.card(
                            "service-unhealthy-" + svc.getId(),
                            "High",
                            svcName,
                            "alert",
                            "Service " + svcName + " is unhealthy",
                            "Cluster reports this service as unhealthy. Workloads using it will fail or degrade.",
                            "Analyzing host logs…",
                            "Service status is healthy in BEAT.");
                    String cfgDump = AdvisoryDetector.formatConfigsForLlm(
                            configsByService.get(svc.getId()), 3500);
                    list.add(advisoryRcaService.enrichWithLogsAndLlm(card, svc.getName(), null, cfgDump));
                }
            }
            list.addAll(AdvisoryDetector.staleRestartCards(
                    services, configsByService, latestSnapByService, dnByService));
            for (ServicePO svc : services) {
                list.addAll(AdvisoryDetector.configReviewCards(
                        svc, configsByService.get(svc.getId()), dnByService.getOrDefault(svc.getId(), 0)));
            }
        }

        List<ComponentPO> unhealthyComponents = componentDao.findByQuery(
                ComponentQuery.builder().status(HealthyStatusEnum.UNHEALTHY.getCode()).build());
        if (unhealthyComponents != null) {
            for (ComponentPO comp : unhealthyComponents) {
                String n = comp.getName() != null ? comp.getName().toLowerCase() : "";
                if (n.endsWith("_client") || "zkfc".equals(n) || "journalnode".equals(n)) {
                    continue;
                }
                String svc = comp.getServiceDisplayName() != null && !comp.getServiceDisplayName().isBlank()
                        ? comp.getServiceDisplayName()
                        : (comp.getServiceName() != null ? comp.getServiceName() : "Service");
                String cname = comp.getDisplayName() != null ? comp.getDisplayName() : comp.getName();
                String host = comp.getHostname() != null ? comp.getHostname() : ("host-" + comp.getHostId());
                AdvisorySuggestionVO card = AdvisoryDetector.card(
                        "component-unhealthy-" + comp.getId(),
                        "High",
                        svc,
                        "alert",
                        cname + " on " + host + " is unhealthy",
                        "A down role breaks quorum, HDFS writes, or YARN scheduling depending on the component.",
                        "Analyzing host logs…",
                        "Component status returns to healthy.");
                String svcKey = comp.getServiceName() != null ? comp.getServiceName() : svc;
                String cfgDump = "";
                if (comp.getServiceId() != null) {
                    cfgDump = AdvisoryDetector.formatConfigsForLlm(
                            serviceConfigDao.findByServiceId(comp.getServiceId()), 3500);
                }
                list.add(advisoryRcaService.enrichWithLogsAndLlm(card, svcKey, host, cfgDump));
            }
        }
        list.addAll(AdvisoryDetector.hdfsSafetyCards("http://127.0.0.1:9870"));
        Object notAfter = alephysStore.tlsStatus().get("notAfter");
        if (notAfter != null) {
            list.addAll(AdvisoryDetector.tlsExpiryCards(String.valueOf(notAfter)));
        }
        list.addAll(AdvisoryDetector.tenantQuotaCards(estatesWithUsage()));

        for (ClusterPO cluster : clusters) {
            if (!isUnhealthy(cluster.getStatus())) {
                continue;
            }
            String cname = cluster.getDisplayName() != null && !cluster.getDisplayName().isBlank()
                    ? cluster.getDisplayName()
                    : cluster.getName();
            list.add(AdvisoryDetector.card(
                    "cluster-unhealthy-" + cluster.getId(),
                    "Medium",
                    "Cluster",
                    "alert",
                    "Cluster " + cname + " is marked unhealthy",
                    "Overall cluster health is unhealthy. Check hosts and services before adding more workload.",
                    "1) Review host and service status in BEAT.\n"
                            + "2) Restore unhealthy components via BEAT Start/Restart.\n"
                            + "3) Re-check cluster health after fixes.",
                    "Cluster status returns to healthy."));
        }

        return list;
    }

    @Override
    public List<AdvisorySuggestionVO> reviewServiceConfigs(Long serviceId) {
        ServicePO svc = serviceDao.findById(serviceId);
        if (svc == null) {
            throw new ApiException(ApiExceptionEnum.SERVICE_NOT_FOUND);
        }
        List<ServiceConfigPO> configs = serviceConfigDao.findByServiceId(serviceId);
        List<AdvisorySuggestionVO> list = new ArrayList<>();
        if (Boolean.TRUE.equals(svc.getRestartFlag())) {
            list.add(AdvisoryDetector.staleRestartCard(
                    svc, configs, latestSnapshotDesc(serviceId), countDataNodes(serviceId)));
        }
        list.addAll(AdvisoryDetector.configReviewCards(svc, configs, countDataNodes(serviceId)));
        return list;
    }

    private String latestSnapshotDesc(Long serviceId) {
        try {
            List<ServiceConfigSnapshotPO> snaps = serviceConfigSnapshotDao.findByServiceId(serviceId);
            if (snaps == null || snaps.isEmpty()) {
                return null;
            }
            // DAO returns newest-first in lab; if not, pick max id
            ServiceConfigSnapshotPO best = snaps.get(0);
            for (ServiceConfigSnapshotPO s : snaps) {
                if (s.getId() != null && (best.getId() == null || s.getId() > best.getId())) {
                    best = s;
                }
            }
            return best.getDesc();
        } catch (Exception e) {
            return null;
        }
    }

    private int countDataNodes(Long serviceId) {
        List<ComponentPO> comps =
                componentDao.findByQuery(ComponentQuery.builder().serviceId(serviceId).build());
        if (comps == null) {
            return 0;
        }
        int n = 0;
        for (ComponentPO c : comps) {
            if ("datanode".equals(c.getName())) {
                n++;
            }
        }
        return n;
    }

    private static boolean isUnhealthy(Integer status) {
        return HealthyStatusEnum.UNHEALTHY == HealthyStatusEnum.fromCode(status);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> estatesWithUsage() {
        List<Map<String, Object>> out = new ArrayList<>();
        Object raw = alephysStore.readEstates();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                row.put(String.valueOf(e.getKey()), e.getValue());
            }
            Object path = row.get("hdfsPath");
            Object quotaGb = row.get("quotaGb");
            if (path != null && quotaGb instanceof Number) {
                Double used = hdfsUsedGb(String.valueOf(path));
                if (used != null) {
                    double q = ((Number) quotaGb).doubleValue();
                    row.put("usedGb", used);
                    if (q > 0) {
                        row.put("usedPercent", Math.round(used * 1000.0 / q) / 10.0);
                    }
                }
            }
            out.add(row);
        }
        return out;
    }

    private static Double hdfsUsedGb(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/opt/services/hadoop/bin/hdfs", "dfs", "-count", path);
            pb.environment().put("HADOOP_CONF_DIR", "/opt/services/hadoop/etc/hadoop");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String[] parts = out.trim().split("\\s+");
            if (parts.length >= 3) {
                long bytes = Long.parseLong(parts[2]);
                return Math.round(bytes / 1024.0 / 1024.0 / 1024.0 * 1000.0) / 1000.0;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
