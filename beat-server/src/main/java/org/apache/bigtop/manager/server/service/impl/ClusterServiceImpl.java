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
import org.apache.bigtop.manager.server.enums.ApiExceptionEnum;
import org.apache.bigtop.manager.server.enums.HealthyStatusEnum;
import org.apache.bigtop.manager.server.exception.ApiException;
import org.apache.bigtop.manager.server.model.converter.ClusterConverter;
import org.apache.bigtop.manager.server.model.dto.ClusterDTO;
import org.apache.bigtop.manager.server.model.vo.ClusterVO;
import org.apache.bigtop.manager.server.service.ClusterService;
import org.apache.bigtop.manager.server.timer.ComponentStatusTimer;

import org.apache.commons.collections4.CollectionUtils;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class ClusterServiceImpl implements ClusterService {

    @Resource
    private ClusterDao clusterDao;

    @Resource
    private HostDao hostDao;

    @Resource
    private ServiceDao serviceDao;

    @Resource
    private ComponentDao componentDao;

    @Resource
    private ServiceConfigDao serviceConfigDao;

    @Override
    public List<ClusterVO> list() {
        List<ClusterPO> clusterPOList = clusterDao.findAll();
        for (ClusterPO clusterPO : clusterPOList) {
            applyDerivedStatus(clusterPO);
        }
        return ClusterConverter.INSTANCE.fromPO2VO(clusterPOList);
    }

    @Override
    public ClusterVO get(Long id) {
        ClusterPO clusterPO = clusterDao.findDetailsById(id);
        if (clusterPO == null) {
            throw new ApiException(ApiExceptionEnum.CLUSTER_NOT_FOUND);
        }

        int serviceNum = serviceDao.countByClusterId(id);
        clusterPO.setTotalService((long) serviceNum);
        applyDerivedStatus(clusterPO);
        return ClusterConverter.INSTANCE.fromPO2VO(clusterPO);
    }

    /** Cluster DB status is stale after create — derive from current service health. */
    private void applyDerivedStatus(ClusterPO clusterPO) {
        List<ServicePO> services = serviceDao.findByClusterId(clusterPO.getId());
        Integer previous = clusterPO.getStatus();
        Integer derived = ComponentStatusTimer.deriveClusterStatus(services);
        clusterPO.setStatus(derived);
        if (!Objects.equals(previous, derived)) {
            ClusterPO patch = new ClusterPO();
            patch.setId(clusterPO.getId());
            patch.setStatus(derived);
            try {
                clusterDao.partialUpdateById(patch);
            } catch (Exception ignored) {
                // read path must not fail on write
            }
        }
    }

    @Override
    public ClusterVO update(Long id, ClusterDTO clusterDTO) {
        ClusterPO clusterPO = ClusterConverter.INSTANCE.fromDTO2PO(clusterDTO);
        clusterPO.setId(id);
        clusterDao.partialUpdateById(clusterPO);

        return get(id);
    }

    @Override
    public Boolean remove(Long id) {
        // Auto-purge orphan services (no components) so Delete Cluster is not blocked
        // after a failed Add Service left a ghost row (e.g. ZooKeeper with 0 components).
        List<ServicePO> servicePOList = serviceDao.findByClusterId(id);
        if (CollectionUtils.isNotEmpty(servicePOList)) {
            for (ServicePO servicePO : servicePOList) {
                ComponentQuery q = ComponentQuery.builder().serviceId(servicePO.getId()).build();
                List<ComponentPO> comps = componentDao.findByQuery(q);
                if (CollectionUtils.isEmpty(comps)
                        && Objects.equals(servicePO.getStatus(), HealthyStatusEnum.UNHEALTHY.getCode())) {
                    try {
                        // best-effort: delete configs then service
                        List<ServiceConfigPO> configs = serviceConfigDao.findByServiceId(servicePO.getId());
                        if (CollectionUtils.isNotEmpty(configs)) {
                            serviceConfigDao.deleteByIds(
                                    configs.stream().map(ServiceConfigPO::getId).toList());
                        }
                        serviceDao.deleteById(servicePO.getId());
                    } catch (Exception e) {
                        log.warn("Failed to purge orphan service {}: {}", servicePO.getName(), e.toString());
                    }
                }
            }
            servicePOList = serviceDao.findByClusterId(id);
            if (CollectionUtils.isNotEmpty(servicePOList)) {
                throw new ApiException(ApiExceptionEnum.CLUSTER_HAS_SERVICES);
            }
        }

        // Detach hosts so an empty cluster can be deleted from the UI.
        // Hosts stay in Host inventory (clusterId=0 = unassigned).
        List<HostPO> hostPOList = hostDao.findAllByClusterId(id);
        if (CollectionUtils.isNotEmpty(hostPOList)) {
            for (HostPO hostPO : hostPOList) {
                HostPO patch = new HostPO();
                patch.setId(hostPO.getId());
                patch.setClusterId(0L);
                hostDao.partialUpdateById(patch);
            }
        }

        return clusterDao.deleteById(id);
    }
}
