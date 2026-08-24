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

import org.apache.bigtop.manager.common.utils.JsonUtils;
import org.apache.bigtop.manager.dao.po.ClusterPO;
import org.apache.bigtop.manager.dao.po.ComponentPO;
import org.apache.bigtop.manager.dao.po.ServiceConfigPO;
import org.apache.bigtop.manager.dao.po.ServiceConfigSnapshotPO;
import org.apache.bigtop.manager.dao.po.ServicePO;
import org.apache.bigtop.manager.dao.query.ComponentQuery;
import org.apache.bigtop.manager.dao.query.ServiceQuery;
import org.apache.bigtop.manager.dao.repository.ClusterDao;
import org.apache.bigtop.manager.dao.repository.ComponentDao;
import org.apache.bigtop.manager.dao.repository.ServiceConfigDao;
import org.apache.bigtop.manager.dao.repository.ServiceConfigSnapshotDao;
import org.apache.bigtop.manager.dao.repository.ServiceDao;
import org.apache.bigtop.manager.dao.repository.UserDao;
import org.apache.bigtop.manager.dao.po.UserPO;
import org.apache.bigtop.manager.server.enums.ApiExceptionEnum;
import org.apache.bigtop.manager.server.enums.HealthyStatusEnum;
import org.apache.bigtop.manager.server.exception.ApiException;
import org.apache.bigtop.manager.server.holder.SessionUserHolder;
import org.apache.bigtop.manager.server.model.converter.ComponentConverter;
import org.apache.bigtop.manager.server.model.converter.ServiceConfigConverter;
import org.apache.bigtop.manager.server.model.converter.ServiceConfigSnapshotConverter;
import org.apache.bigtop.manager.server.model.converter.ServiceConverter;
import org.apache.bigtop.manager.server.model.dto.ServiceConfigDTO;
import org.apache.bigtop.manager.server.model.query.PageQuery;
import org.apache.bigtop.manager.server.model.req.ServiceConfigReq;
import org.apache.bigtop.manager.server.model.req.ServiceConfigSnapshotReq;
import org.apache.bigtop.manager.server.model.vo.ComponentVO;
import org.apache.bigtop.manager.server.model.vo.PageVO;
import org.apache.bigtop.manager.server.model.vo.ServiceConfigSnapshotVO;
import org.apache.bigtop.manager.server.model.vo.ServiceConfigVO;
import org.apache.bigtop.manager.server.model.vo.ServiceUserVO;
import org.apache.bigtop.manager.server.model.vo.RoleProcessVO;
import org.apache.bigtop.manager.server.model.vo.ServiceVO;
import org.apache.bigtop.manager.server.service.ServiceService;
import org.apache.bigtop.manager.server.service.alephys.AlephysStore;
import org.apache.bigtop.manager.server.utils.PageUtils;
import org.apache.bigtop.manager.server.utils.StackConfigUtils;
import org.apache.bigtop.manager.server.utils.StackUtils;

import org.apache.commons.collections4.CollectionUtils;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class ServiceServiceImpl implements ServiceService {

    @Resource
    private ClusterDao clusterDao;

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
    private UserDao userDao;

    @Override
    public PageVO<ServiceVO> list(ServiceQuery query) {
        PageQuery pageQuery = PageUtils.getPageQuery();
        try (Page<?> ignored =
                PageHelper.startPage(pageQuery.getPageNum(), pageQuery.getPageSize(), pageQuery.getOrderBy())) {
            List<ServicePO> servicePOList = serviceDao.findByQuery(query);
            PageInfo<ServicePO> pageInfo = new PageInfo<>(servicePOList);
            PageVO<ServiceVO> page = PageVO.of(pageInfo);
            if (page.getContent() != null) {
                for (ServiceVO vo : page.getContent()) {
                    enrichProcess(vo);
                }
            }
            return page;
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public PageVO<ServiceUserVO> serviceUsers(Long clusterId) {
        PageQuery pageQuery = PageUtils.getPageQuery();
        try (Page<?> ignored =
                PageHelper.startPage(pageQuery.getPageNum(), pageQuery.getPageSize(), pageQuery.getOrderBy())) {
            ServiceQuery query = ServiceQuery.builder().clusterId(clusterId).build();
            List<ServicePO> servicePOList = serviceDao.findByQuery(query);

            ClusterPO clusterPO = clusterDao.findById(clusterId);
            List<ServiceUserVO> res = new ArrayList<>();
            for (ServicePO servicePO : servicePOList) {
                ServiceUserVO serviceUserVO = new ServiceUserVO();
                serviceUserVO.setDisplayName(servicePO.getDisplayName());
                serviceUserVO.setUser(servicePO.getUser());
                serviceUserVO.setUserGroup(clusterPO.getUserGroup());
                serviceUserVO.setDesc(servicePO.getDesc());
                res.add(serviceUserVO);
            }

            PageInfo<ServicePO> pageInfo = new PageInfo<>(servicePOList);
            return PageVO.of(res, pageInfo.getTotal());
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public ServiceVO get(Long id) {
        ServiceVO serviceVO = ServiceConverter.INSTANCE.fromPO2VO(serviceDao.findById(id));

        ComponentQuery query = ComponentQuery.builder().serviceId(id).build();
        List<ComponentPO> componentPOList = componentDao.findByQuery(query);
        List<ComponentVO> componentVOList = ComponentConverter.INSTANCE.fromPO2VO(componentPOList);
        List<ServiceConfigVO> serviceConfigVOList = listConf(null, id);

        serviceVO.setComponents(componentVOList);
        serviceVO.setConfigs(serviceConfigVOList);
        enrichProcess(serviceVO);
        return serviceVO;
    }

    @Override
    public ServiceVO getByProcess(Long clusterId, String processKey) {
        Map<String, Object> row = alephysStore.resolveProcess(processKey);
        if (row.isEmpty()) {
            // Fallback: treat as numeric service id (legacy bookmarks)
            try {
                return get(Long.parseLong(processKey));
            } catch (NumberFormatException e) {
                throw new ApiException(ApiExceptionEnum.SERVICE_NOT_FOUND);
            }
        }
        Object sid = row.get("serviceId");
        if (sid == null) {
            throw new ApiException(ApiExceptionEnum.SERVICE_NOT_FOUND);
        }
        return get(Long.parseLong(String.valueOf(sid)));
    }

    private void enrichProcess(ServiceVO serviceVO) {
        if (serviceVO == null || serviceVO.getId() == null) {
            return;
        }
        ServicePO po = serviceDao.findById(serviceVO.getId());
        if (po == null) {
            return;
        }
        List<Map<String, Object>> roleRows =
                alephysStore.listProcessesForService(po.getClusterId(), po.getName());
        List<RoleProcessVO> roleProcesses = new ArrayList<>();
        for (Map<String, Object> row : roleRows) {
            RoleProcessVO rp = new RoleProcessVO();
            rp.setComponentName(String.valueOf(row.getOrDefault("componentName", "")));
            try {
                var dto = StackUtils.getComponentDTO(rp.getComponentName());
                if (dto != null && dto.getDisplayName() != null) {
                    rp.setDisplayName(dto.getDisplayName());
                }
            } catch (Exception ignored) {
                // display name optional
            }
            if (rp.getDisplayName() == null || rp.getDisplayName().isBlank()) {
                rp.setDisplayName(rp.getComponentName());
            }
            Object processId = row.get("processId");
            Object generation = row.get("processGeneration");
            Object processDir = row.get("processDir");
            if (processId != null) {
                rp.setProcessId(String.valueOf(processId));
            }
            if (generation != null) {
                rp.setProcessGeneration(Long.parseLong(String.valueOf(generation)));
            }
            if (processDir != null) {
                rp.setProcessDir(String.valueOf(processDir));
            }
            roleProcesses.add(rp);
        }
        serviceVO.setRoleProcesses(roleProcesses);

        Map<String, Object> row = roleRows.isEmpty()
                ? alephysStore.latestProcessForServiceId(po.getClusterId(), po.getId())
                : roleRows.get(roleRows.size() - 1);
        if (row.isEmpty()) {
            row = alephysStore.latestProcessForService(po.getClusterId(), po.getName());
        }
        if (row.isEmpty()) {
            return;
        }
        Object processId = row.get("processId");
        Object generation = row.get("processGeneration");
        Object processDir = row.get("processDir");
        if (processId != null) {
            serviceVO.setProcessId(String.valueOf(processId));
        }
        if (generation != null) {
            serviceVO.setProcessGeneration(Long.parseLong(String.valueOf(generation)));
        }
        if (processDir != null) {
            serviceVO.setProcessDir(String.valueOf(processDir));
        }
    }

    @Override
    @Transactional
    public Boolean remove(Long id) {
        ServicePO servicePO = serviceDao.findById(id);
        if (servicePO == null) {
            throw new ApiException(ApiExceptionEnum.SERVICE_NOT_FOUND);
        }

        // Check if required by other installed services
        List<String> requiredBy = StackUtils.getServiceRequiredBy(servicePO.getName());
        if (CollectionUtils.isNotEmpty(requiredBy)) {
            boolean isInfra = servicePO.getClusterId() == 0;
            List<ServicePO> servicePOList;
            if (isInfra) {
                servicePOList = serviceDao.findByClusterIdAndNames(null, requiredBy);
            } else {
                servicePOList = serviceDao.findByClusterIdAndNames(servicePO.getClusterId(), requiredBy);
            }

            if (CollectionUtils.isNotEmpty(servicePOList)) {
                throw new ApiException(
                        ApiExceptionEnum.SERVICE_REQUIRED_BY,
                        servicePOList.get(0).getDisplayName());
            }
        }

        // Check service status - only allow deletion when service is stopped
        if (!Objects.equals(servicePO.getStatus(), HealthyStatusEnum.UNHEALTHY.getCode())) {
            throw new ApiException(ApiExceptionEnum.SERVICE_IS_RUNNING);
        }

        ComponentQuery query = ComponentQuery.builder().serviceId(id).build();
        List<ComponentPO> componentPOList = componentDao.findByQuery(query);

        // Check all components status - only allow deletion when all components are stopped
        // Skip client components as they are always healthy
        for (ComponentPO componentPO : componentPOList) {
            if (StackUtils.isClientComponent(componentPO.getName())) {
                continue;
            }
            if (!Objects.equals(componentPO.getStatus(), HealthyStatusEnum.UNHEALTHY.getCode())) {
                throw new ApiException(ApiExceptionEnum.COMPONENT_IS_RUNNING);
            }
        }

        // Delete all components first (skip empty IN () which breaks PostgreSQL)
        if (CollectionUtils.isNotEmpty(componentPOList)) {
            componentDao.deleteByIds(
                    componentPOList.stream().map(ComponentPO::getId).toList());
        }

        // Delete all service configurations
        List<ServiceConfigPO> configPOList = serviceConfigDao.findByServiceId(id);
        if (CollectionUtils.isNotEmpty(configPOList)) {
            serviceConfigDao.deleteByIds(
                    configPOList.stream().map(ServiceConfigPO::getId).toList());
        }

        // Delete all service config snapshots
        List<ServiceConfigSnapshotPO> snapshotPOList = serviceConfigSnapshotDao.findByServiceId(id);
        if (CollectionUtils.isNotEmpty(snapshotPOList)) {
            serviceConfigSnapshotDao.deleteByIds(
                    snapshotPOList.stream().map(ServiceConfigSnapshotPO::getId).toList());
        }

        // Finally delete the service
        return serviceDao.deleteById(id);
    }

    @Override
    public List<ServiceConfigVO> listConf(Long clusterId, Long serviceId) {
        List<ServiceConfigPO> list = serviceConfigDao.findByServiceId(serviceId);
        if (CollectionUtils.isEmpty(list)) {
            return List.of();
        }
        ServicePO servicePO = serviceDao.findById(serviceId);
        List<ServiceConfigDTO> stackConfigs =
                servicePO != null ? StackUtils.SERVICE_CONFIG_MAP.get(servicePO.getName()) : null;
        if (CollectionUtils.isEmpty(stackConfigs)) {
            return ServiceConfigConverter.INSTANCE.fromPO2VO(list);
        }
        List<ServiceConfigDTO> dbConfigs = ServiceConfigConverter.INSTANCE.fromPO2DTO(list);
        List<ServiceConfigDTO> merged = StackConfigUtils.mergeServiceConfigs(stackConfigs, dbConfigs);
        return ServiceConfigConverter.INSTANCE.fromPO2VO(ServiceConfigConverter.INSTANCE.fromDTO2PO(merged));
    }

    @Override
    public List<ServiceConfigVO> updateConf(Long clusterId, Long serviceId, List<ServiceConfigReq> reqs) {
        ServicePO servicePO = serviceDao.findById(serviceId);
        if (servicePO == null) {
            throw new ApiException(ApiExceptionEnum.SERVICE_NOT_FOUND);
        }
        List<ServiceConfigPO> configs = serviceConfigDao.findByServiceId(serviceId);

        List<ServiceConfigDTO> oriConfigs;
        List<ServiceConfigDTO> newConfigs;
        List<ServiceConfigDTO> mergedConfigs;

        // Merge stack config with existing config first, in case new property has been added to config xml.
        oriConfigs = StackUtils.SERVICE_CONFIG_MAP.get(servicePO.getName());
        newConfigs = ServiceConfigConverter.INSTANCE.fromPO2DTO(configs);
        mergedConfigs = StackConfigUtils.mergeServiceConfigs(oriConfigs, newConfigs);

        // Merge existing config with new config in request object
        oriConfigs = mergedConfigs;
        newConfigs = ServiceConfigConverter.INSTANCE.fromReq2DTO(reqs);
        mergedConfigs = StackConfigUtils.mergeServiceConfigs(oriConfigs, newConfigs);

        // CM-style: record history BEFORE applying so Revert can restore prior values
        if (CollectionUtils.isNotEmpty(reqs)) {
            recordConfigHistory(serviceId, configs, mergedConfigs, false, null);
        }

        // Save merged config
        List<ServiceConfigPO> serviceConfigPOList = ServiceConfigConverter.INSTANCE.fromDTO2PO(mergedConfigs);
        serviceConfigDao.partialUpdateByIds(serviceConfigPOList);
        if (CollectionUtils.isNotEmpty(reqs)) {
            servicePO.setRestartFlag(true);
            serviceDao.partialUpdateById(servicePO);
        }
        return listConf(clusterId, serviceId);
    }

    @Override
    public List<ServiceConfigSnapshotVO> listConfSnapshots(Long clusterId, Long serviceId) {
        List<ServiceConfigSnapshotPO> list = serviceConfigSnapshotDao.findByServiceId(serviceId);
        if (CollectionUtils.isEmpty(list)) {
            return List.of();
        } else {
            List<ServiceConfigSnapshotVO> vos = ServiceConfigSnapshotConverter.INSTANCE.fromPO2VO(list);
            // newest first like CM Configuration History
            vos.sort((a, b) -> {
                String ta = a.getCreateTime() == null ? "" : a.getCreateTime();
                String tb = b.getCreateTime() == null ? "" : b.getCreateTime();
                return tb.compareTo(ta);
            });
            return vos;
        }
    }

    @Override
    public ServiceConfigSnapshotVO takeConfSnapshot(Long clusterId, Long serviceId, ServiceConfigSnapshotReq req) {
        List<ServiceConfigPO> list = serviceConfigDao.findByServiceId(serviceId);
        Map<String, String> confMap = new HashMap<>();
        for (ServiceConfigPO serviceConfigPO : list) {
            confMap.put(serviceConfigPO.getName(), serviceConfigPO.getPropertiesJson());
        }

        String confJson = JsonUtils.writeAsString(confMap);
        ServiceConfigSnapshotPO serviceConfigSnapshotPO = new ServiceConfigSnapshotPO();
        serviceConfigSnapshotPO.setName(req.getName());
        serviceConfigSnapshotPO.setDesc(req.getDesc());
        serviceConfigSnapshotPO.setConfigJson(confJson);
        serviceConfigSnapshotPO.setServiceId(serviceId);
        serviceConfigSnapshotDao.save(serviceConfigSnapshotPO);
        return ServiceConfigSnapshotConverter.INSTANCE.fromPO2VO(serviceConfigSnapshotPO);
    }

    @Override
    public List<ServiceConfigVO> recoveryConfSnapshot(Long clusterId, Long serviceId, Long snapshotId) {
        ServiceConfigSnapshotPO serviceConfigSnapshotPO = serviceConfigSnapshotDao.findById(snapshotId);
        if (serviceConfigSnapshotPO == null) {
            throw new ApiException(ApiExceptionEnum.SERVICE_NOT_FOUND);
        }

        List<ServiceConfigPO> beforeRevert = serviceConfigDao.findByServiceId(serviceId);
        Map<String, String> confMap = JsonUtils.readFromString(serviceConfigSnapshotPO.getConfigJson());

        // Build "after" DTOs from snapshot so history shows the revert diff
        List<ServiceConfigDTO> afterDtos = new ArrayList<>();
        for (ServiceConfigPO po : beforeRevert) {
            ServiceConfigDTO dto = new ServiceConfigDTO();
            dto.setName(po.getName());
            String json = confMap.getOrDefault(po.getName(), po.getPropertiesJson());
            Map<String, String> flat = flattenProps(json);
            List<org.apache.bigtop.manager.server.model.dto.PropertyDTO> props = new ArrayList<>();
            for (Map.Entry<String, String> e : flat.entrySet()) {
                org.apache.bigtop.manager.server.model.dto.PropertyDTO p =
                        new org.apache.bigtop.manager.server.model.dto.PropertyDTO();
                p.setName(e.getKey());
                p.setValue(e.getValue());
                props.add(p);
            }
            dto.setProperties(props);
            afterDtos.add(dto);
        }

        String originalMsg = serviceConfigSnapshotPO.getName();
        recordConfigHistory(serviceId, beforeRevert, afterDtos, true, originalMsg);

        List<ServiceConfigPO> list = serviceConfigDao.findByServiceId(serviceId);
        for (ServiceConfigPO serviceConfigPO : list) {
            String value = confMap.get(serviceConfigPO.getName());
            if (value != null) {
                serviceConfigPO.setPropertiesJson(value);
            }
        }

        serviceConfigDao.updateByIds(list);
        ServicePO servicePO = serviceDao.findById(serviceId);
        if (servicePO != null) {
            servicePO.setRestartFlag(true);
            serviceDao.partialUpdateById(servicePO);
        }
        return ServiceConfigConverter.INSTANCE.fromPO2VO(list);
    }

    /**
     * Persist a CM-style config revision. {@code configJson} stores the prior live state so
     * {@link #recoveryConfSnapshot} can restore it. Diff details live in {@code desc} as JSON.
     */
    private void recordConfigHistory(
            Long serviceId,
            List<ServiceConfigPO> beforePos,
            List<ServiceConfigDTO> afterDtos,
            boolean reverted,
            String revertOfMessage) {
        try {
            Map<String, String> beforeMap = new HashMap<>();
            for (ServiceConfigPO po : beforePos) {
                beforeMap.put(po.getName(), po.getPropertiesJson());
            }

            Map<String, Map<String, String>> beforeProps = propsByFile(beforeMap);
            Map<String, Map<String, String>> afterProps = propsByFileFromDtos(afterDtos);

            List<Map<String, Object>> changes = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> fileEntry : afterProps.entrySet()) {
                String file = fileEntry.getKey();
                Map<String, String> afterFile = fileEntry.getValue();
                Map<String, String> beforeFile = beforeProps.getOrDefault(file, Map.of());
                for (Map.Entry<String, String> prop : afterFile.entrySet()) {
                    String oldVal = beforeFile.get(prop.getKey());
                    String newVal = prop.getValue();
                    if (!Objects.equals(oldVal, newVal)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("file", file);
                        row.put("property", prop.getKey());
                        row.put("oldValue", oldVal == null ? "" : oldVal);
                        row.put("newValue", newVal == null ? "" : newVal);
                        changes.add(row);
                    }
                }
                // deleted props
                for (String key : beforeFile.keySet()) {
                    if (!afterFile.containsKey(key)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("file", file);
                        row.put("property", key);
                        row.put("oldValue", beforeFile.get(key));
                        row.put("newValue", "");
                        changes.add(row);
                    }
                }
            }

            if (!reverted && changes.isEmpty()) {
                return;
            }

            String username = resolveUsername();
            String message;
            if (reverted) {
                message = "Reverted: " + (revertOfMessage == null ? "configuration" : revertOfMessage);
            } else if (changes.size() == 1) {
                Map<String, Object> c = changes.get(0);
                message = c.get("property") + ": " + c.get("oldValue") + " → " + c.get("newValue");
            } else {
                message = "Updated " + changes.size() + " properties ("
                        + changes.stream()
                                .limit(3)
                                .map(c -> String.valueOf(c.get("property")))
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("")
                        + (changes.size() > 3 ? ", …" : "")
                        + ")";
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("message", message);
            meta.put("username", username);
            meta.put("reverted", reverted);
            meta.put("changes", changes);

            ServiceConfigSnapshotPO snap = new ServiceConfigSnapshotPO();
            snap.setServiceId(serviceId);
            snap.setName(message.length() > 200 ? message.substring(0, 200) : message);
            snap.setDesc(JsonUtils.writeAsString(meta));
            // Prior live state — Revert restores this
            snap.setConfigJson(JsonUtils.writeAsString(beforeMap));
            serviceConfigSnapshotDao.save(snap);
        } catch (Exception e) {
            log.warn("Failed to record config history for service {}: {}", serviceId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> propsByFile(Map<String, String> confMap) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (Map.Entry<String, String> e : confMap.entrySet()) {
            out.put(e.getKey(), flattenProps(e.getValue()));
        }
        return out;
    }

    private static Map<String, Map<String, String>> propsByFileFromDtos(List<ServiceConfigDTO> dtos) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (dtos == null) {
            return out;
        }
        for (ServiceConfigDTO dto : dtos) {
            Map<String, String> props = new HashMap<>();
            if (dto.getProperties() != null) {
                for (var p : dto.getProperties()) {
                    if (p.getName() != null) {
                        props.put(p.getName(), p.getValue() == null ? "" : String.valueOf(p.getValue()));
                    }
                }
            }
            out.put(dto.getName(), props);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> flattenProps(String propertiesJson) {
        Map<String, String> props = new HashMap<>();
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return props;
        }
        try {
            List<Map<String, Object>> list = JsonUtils.readFromString(propertiesJson, List.class);
            if (list == null) {
                return props;
            }
            for (Map<String, Object> p : list) {
                Object name = p.get("name");
                if (name == null) {
                    continue;
                }
                Object value = p.get("value");
                props.put(String.valueOf(name), value == null ? "" : String.valueOf(value));
            }
        } catch (Exception ignored) {
            // leave empty
        }
        return props;
    }

    private String resolveUsername() {
        try {
            Long uid = SessionUserHolder.getUserId();
            if (uid == null) {
                return "admin";
            }
            UserPO user = userDao.findById(uid);
            if (user != null && user.getUsername() != null) {
                return user.getUsername();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "admin";
    }

    @Override
    public Boolean deleteConfSnapshot(Long clusterId, Long serviceId, Long snapshotId) {
        return serviceConfigSnapshotDao.deleteById(snapshotId);
    }
}
