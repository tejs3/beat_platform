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
package org.apache.bigtop.manager.server.timer;

import org.apache.bigtop.manager.common.constants.ComponentCategories;
import org.apache.bigtop.manager.common.utils.CaseUtils;
import org.apache.bigtop.manager.dao.po.ClusterPO;
import org.apache.bigtop.manager.dao.po.ComponentPO;
import org.apache.bigtop.manager.dao.po.HostPO;
import org.apache.bigtop.manager.dao.po.ServicePO;
import org.apache.bigtop.manager.dao.repository.ClusterDao;
import org.apache.bigtop.manager.dao.repository.ComponentDao;
import org.apache.bigtop.manager.dao.repository.HostDao;
import org.apache.bigtop.manager.dao.repository.ServiceDao;
import org.apache.bigtop.manager.grpc.generated.ComponentStatusReply;
import org.apache.bigtop.manager.grpc.generated.ComponentStatusRequest;
import org.apache.bigtop.manager.grpc.generated.ComponentStatusServiceGrpc;
import org.apache.bigtop.manager.server.enums.HealthyStatusEnum;
import org.apache.bigtop.manager.server.grpc.GrpcClient;
import org.apache.bigtop.manager.server.model.dto.ComponentDTO;
import org.apache.bigtop.manager.server.utils.StackUtils;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ComponentStatusTimer {

    @Resource
    private ServiceDao serviceDao;

    @Resource
    private ComponentDao componentDao;

    @Resource
    private HostDao hostDao;

    @Resource
    private ClusterDao clusterDao;

    @Async
    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void execute() {
        if (!StackUtils.isStackParsed()) {
            return;
        }

        List<ComponentPO> componentPOList = componentDao.findAll();
        for (ComponentPO componentPO : componentPOList) {
            ComponentDTO componentDTO;
            try {
                componentDTO = StackUtils.getComponentDTO(componentPO.getName());
            } catch (Exception e) {
                log.warn("Skip status check for unknown component {}: {}", componentPO.getName(), e.getMessage());
                continue;
            }
            String category = componentDTO.getCategory();
            if (HealthyStatusEnum.fromCode(componentPO.getStatus()) == HealthyStatusEnum.UNKNOWN
                    || category.equals(ComponentCategories.CLIENT)) {
                continue;
            }

            try {
                ComponentPO componentDetailsPO = componentDao.findDetailsById(componentPO.getId());
                HostPO hostPO = hostDao.findById(componentPO.getHostId());
                ComponentStatusRequest request = ComponentStatusRequest.newBuilder()
                        .setStackName(
                                CaseUtils.toLowerCase(componentDetailsPO.getStack().split("-")[0]))
                        .setStackVersion(componentDetailsPO.getStack().split("-")[1])
                        .setServiceName(componentDetailsPO.getServiceName())
                        .setServiceUser(componentDetailsPO.getServiceUser())
                        .setComponentName(componentDetailsPO.getName())
                        .build();
                ComponentStatusServiceGrpc.ComponentStatusServiceBlockingStub blockingStub =
                        GrpcClient.getBlockingStub(
                                hostPO.getHostname(),
                                hostPO.getGrpcPort(),
                                ComponentStatusServiceGrpc.ComponentStatusServiceBlockingStub.class);
                ComponentStatusReply reply = blockingStub.getComponentStatus(request);

                // Status 0 means the service is running
                if (reply.getStatus() == 0) {
                    componentPO.setStatus(HealthyStatusEnum.HEALTHY.getCode());
                } else if (Objects.equals(componentPO.getStatus(), HealthyStatusEnum.STOPPED.getCode())) {
                    // Keep intentional stop — do not flip to UNHEALTHY
                    componentPO.setStatus(HealthyStatusEnum.STOPPED.getCode());
                } else {
                    componentPO.setStatus(HealthyStatusEnum.UNHEALTHY.getCode());
                }
            } catch (Exception e) {
                log.warn(
                        "Status check failed for {} on hostId={}: {}",
                        componentPO.getName(),
                        componentPO.getHostId(),
                        e.getMessage());
            }
        }

        componentDao.partialUpdateByIds(componentPOList);

        // Update services (ignore CLIENT components — they are not status-checked)
        Map<Long, List<ComponentPO>> componentPOMap =
                componentPOList.stream().collect(Collectors.groupingBy(ComponentPO::getServiceId));
        for (Map.Entry<Long, List<ComponentPO>> entry : componentPOMap.entrySet()) {
            Long serviceId = entry.getKey();
            List<ComponentPO> components = entry.getValue().stream()
                    .filter(component -> {
                        try {
                            ComponentDTO dto = StackUtils.getComponentDTO(component.getName());
                            return dto != null && !ComponentCategories.CLIENT.equals(dto.getCategory());
                        } catch (Exception e) {
                            return true;
                        }
                    })
                    .toList();
            if (components.isEmpty()) {
                continue;
            }

            ServicePO servicePO = serviceDao.findById(serviceId);
            boolean hasUnknownComponent = components.stream()
                    .anyMatch(component -> Objects.equals(component.getStatus(), HealthyStatusEnum.UNKNOWN.getCode()));
            if (hasUnknownComponent) {
                continue;
            }

            boolean allHealthy = components.stream()
                    .allMatch(component -> Objects.equals(component.getStatus(), HealthyStatusEnum.HEALTHY.getCode()));
            boolean allStopped = components.stream()
                    .allMatch(component -> Objects.equals(component.getStatus(), HealthyStatusEnum.STOPPED.getCode()));
            boolean anyUnhealthy = components.stream()
                    .anyMatch(component -> Objects.equals(component.getStatus(), HealthyStatusEnum.UNHEALTHY.getCode()));

            if (allHealthy) {
                servicePO.setStatus(HealthyStatusEnum.HEALTHY.getCode());
            } else if (allStopped) {
                servicePO.setStatus(HealthyStatusEnum.STOPPED.getCode());
            } else if (anyUnhealthy) {
                servicePO.setStatus(HealthyStatusEnum.UNHEALTHY.getCode());
            } else {
                // mix of healthy + stopped
                servicePO.setStatus(HealthyStatusEnum.UNHEALTHY.getCode());
            }

            serviceDao.partialUpdateById(servicePO);
        }

        // Roll service health up to cluster (was never updated after create)
        List<ClusterPO> clusters = clusterDao.findAll();
        for (ClusterPO clusterPO : clusters) {
            List<ServicePO> services = serviceDao.findByClusterId(clusterPO.getId());
            Integer status = deriveClusterStatus(services);
            if (!Objects.equals(clusterPO.getStatus(), status)) {
                clusterPO.setStatus(status);
                clusterDao.partialUpdateById(clusterPO);
            }
        }
    }

    public static Integer deriveClusterStatus(List<ServicePO> services) {
        if (services == null || services.isEmpty()) {
            return HealthyStatusEnum.HEALTHY.getCode();
        }
        boolean anyUnhealthy = services.stream()
                .anyMatch(s -> Objects.equals(s.getStatus(), HealthyStatusEnum.UNHEALTHY.getCode()));
        if (anyUnhealthy) {
            return HealthyStatusEnum.UNHEALTHY.getCode();
        }
        boolean anyUnknown = services.stream()
                .anyMatch(s -> Objects.equals(s.getStatus(), HealthyStatusEnum.UNKNOWN.getCode()));
        if (anyUnknown) {
            return HealthyStatusEnum.UNKNOWN.getCode();
        }
        return HealthyStatusEnum.HEALTHY.getCode();
    }
}
