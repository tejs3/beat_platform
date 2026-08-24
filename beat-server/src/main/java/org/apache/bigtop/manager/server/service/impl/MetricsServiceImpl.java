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
import org.apache.bigtop.manager.dao.po.ServicePO;
import org.apache.bigtop.manager.dao.query.ComponentQuery;
import org.apache.bigtop.manager.dao.repository.ClusterDao;
import org.apache.bigtop.manager.dao.repository.ComponentDao;
import org.apache.bigtop.manager.dao.repository.HostDao;
import org.apache.bigtop.manager.dao.repository.ServiceConfigDao;
import org.apache.bigtop.manager.dao.repository.ServiceDao;
import org.apache.bigtop.manager.server.model.converter.ServiceConfigConverter;
import org.apache.bigtop.manager.server.model.dto.PropertyDTO;
import org.apache.bigtop.manager.server.model.dto.ServiceConfigDTO;
import org.apache.bigtop.manager.server.model.vo.ClusterMetricsVO;
import org.apache.bigtop.manager.server.model.vo.HostMetricsVO;
import org.apache.bigtop.manager.server.model.vo.ServiceMetricsVO;
import org.apache.bigtop.manager.server.prometheus.PrometheusProxy;
import org.apache.bigtop.manager.server.service.MetricsService;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MetricsServiceImpl implements MetricsService {

    @Resource
    private ClusterDao clusterDao;

    @Resource
    private HostDao hostDao;

    @Resource
    private ComponentDao componentDao;

    @Resource
    private ServiceDao serviceDao;

    @Resource
    private ServiceConfigDao serviceConfigDao;

    @Override
    public HostMetricsVO hostMetrics(Long id, String interval) {
        PrometheusProxy proxy = getProxy();
        if (proxy == null) {
            return new HostMetricsVO();
        }

        String ipv4 = hostDao.findById(id).getIpv4();
        return proxy.queryHostMetrics(ipv4, interval);
    }

    @Override
    public ClusterMetricsVO clusterMetrics(Long clusterId, String interval) {
        PrometheusProxy proxy = getProxy();
        if (proxy != null) {
            try {
                List<String> ipv4s = hostDao.findAllByClusterId(clusterId).stream()
                        .map(HostPO::getIpv4)
                        .toList();
                ClusterMetricsVO vo = proxy.queryClusterMetrics(ipv4s, interval);
                if (vo != null && vo.getMemoryUsageCur() != null) {
                    return vo;
                }
            } catch (Exception e) {
                log.warn("Prometheus cluster metrics failed, using host snapshot: {}", e.getMessage());
            }
        }
        return fallbackClusterMetrics(clusterId);
    }

    private ClusterMetricsVO fallbackClusterMetrics(Long clusterId) {
        List<HostPO> hosts = hostDao.findAllByClusterId(clusterId);
        long total = 0L;
        long free = 0L;
        for (HostPO host : hosts) {
            if (host.getTotalMemorySize() != null) {
                total += host.getTotalMemorySize();
            }
            if (host.getFreeMemorySize() != null) {
                free += host.getFreeMemorySize();
            }
        }
        String memPct = total > 0
                ? String.format("%.2f", (total - Math.min(free, total)) * 100.0 / total)
                : "0.00";
        ClusterMetricsVO vo = new ClusterMetricsVO();
        vo.setMemoryUsageCur(memPct);
        vo.setCpuUsageCur("0.00");
        List<String> timestamps = new ArrayList<>();
        List<String> memoryUsage = new ArrayList<>();
        List<String> cpuUsage = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 5; i >= 0; i--) {
            timestamps.add(String.valueOf(now - i * 60_000L));
            memoryUsage.add(memPct);
            cpuUsage.add("0.00");
        }
        vo.setTimestamps(timestamps);
        vo.setMemoryUsage(memoryUsage);
        vo.setCpuUsage(cpuUsage);
        return vo;
    }

    @Override
    public ServiceMetricsVO serviceMetrics(Long serviceId, String interval) {
        PrometheusProxy proxy = getProxy();
        if (proxy == null) {
            return new ServiceMetricsVO();
        }

        ServicePO servicePO = serviceDao.findById(serviceId);
        ClusterPO clusterPO = clusterDao.findById(servicePO.getClusterId());

        ComponentQuery query = ComponentQuery.builder().serviceId(serviceId).build();
        List<ComponentPO> components = componentDao.findByQuery(query);
        List<String> agentIpv4s = new ArrayList<>();
        for (ComponentPO component : components) {
            HostPO host = hostDao.findById(component.getHostId());
            if (host == null) {
                continue;
            }
            String ip = host.getIpv4();
            if (ip == null || ip.isBlank() || "0.0.0.0".equals(ip)) {
                continue;
            }
            if (!agentIpv4s.contains(ip)) {
                agentIpv4s.add(ip);
            }
        }

        return proxy.queryServiceMetrics(clusterPO.getName(), servicePO.getName(), interval, agentIpv4s);
    }

    private PrometheusProxy getProxy() {
        ComponentQuery query =
                ComponentQuery.builder().name("prometheus_server").build();
        List<ComponentPO> componentPOList = componentDao.findByQuery(query);
        if (componentPOList.isEmpty()) {
            return null;
        } else {
            ComponentPO componentPO = componentPOList.get(0);
            HostPO hostPO = hostDao.findById(componentPO.getHostId());
            ServiceConfigPO serviceConfigPO =
                    serviceConfigDao.findByServiceIdAndName(componentPO.getServiceId(), "prometheus");
            int port = 9090;
            if (serviceConfigPO != null) {
                ServiceConfigDTO serviceConfigDTO = ServiceConfigConverter.INSTANCE.fromPO2DTO(serviceConfigPO);
                if (serviceConfigDTO.getProperties() != null) {
                    for (PropertyDTO property : serviceConfigDTO.getProperties()) {
                        if ("port".equals(property.getName()) && property.getValue() != null) {
                            try {
                                int parsed = Integer.parseInt(property.getValue());
                                if (parsed > 0) {
                                    port = parsed;
                                }
                            } catch (NumberFormatException ignored) {
                                port = 9090;
                            }
                        }
                    }
                }
            }

            // Prefer IPv4 — lab DNS/FQDN often fails inside the server JVM
            String endpoint = hostPO.getIpv4() != null && !hostPO.getIpv4().isBlank()
                    ? hostPO.getIpv4()
                    : hostPO.getHostname();
            return new PrometheusProxy(endpoint, port);
        }
    }
}
