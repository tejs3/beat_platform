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
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.bigtop.manager.server.controller;

import org.apache.bigtop.manager.dao.po.AuditLogPO;
import org.apache.bigtop.manager.dao.repository.AuditLogDao;
import org.apache.bigtop.manager.server.service.alephys.AlephysStore;
import org.apache.bigtop.manager.server.utils.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "BEAT Platform Controller")
@RestController
@RequestMapping("/beat")
public class BeatController {

    @Resource
    private AlephysStore store;

    @Resource
    private org.apache.bigtop.manager.server.service.alephys.AlephysIdentityService identityService;

    @Resource
    private org.apache.bigtop.manager.server.service.alephys.AlephysEvidenceService evidenceService;

    @Resource
    private AuditLogDao auditLogDao;

    @Operation(summary = "estates", description = "Tenant estate descriptors")
    @GetMapping("/estates")
    public ResponseEntity<Object> estates() {
        return ResponseEntity.success(store.readEstates());
    }

    @PutMapping("/estates")
    public ResponseEntity<Object> saveEstates(@RequestBody Object body) {
        return ResponseEntity.success(store.writeEstates(body));
    }

    @Operation(summary = "parcels", description = "BEAT runtime parcels (.parcel only)")
    @GetMapping("/parcels")
    public ResponseEntity<Object> parcels() {
        return ResponseEntity.success(store.listParcels());
    }

    @GetMapping("/parcels/state")
    public ResponseEntity<Object> parcelState() {
        return ResponseEntity.success(store.readParcelState());
    }

    @GetMapping("/parcels/services")
    public ResponseEntity<Object> parcelServices() {
        return ResponseEntity.success(store.listParcelServices());
    }

    @PostMapping("/parcels/repo-url")
    public ResponseEntity<Object> parcelRepoUrl(@RequestBody Map<String, Object> body) {
        String url = body == null ? null : String.valueOf(body.getOrDefault("repoUrl", ""));
        return ResponseEntity.success(store.setParcelRepoUrl(url));
    }

    @PostMapping("/parcels/activate")
    public ResponseEntity<Object> activateParcel(@RequestBody Map<String, Object> body) {
        String name = body == null ? null : String.valueOf(body.getOrDefault("name", ""));
        return ResponseEntity.success(store.activateParcel(name));
    }

    @PostMapping("/parcels/deactivate")
    public ResponseEntity<Object> deactivateParcel(@RequestBody Map<String, Object> body) {
        String name = body == null ? null : String.valueOf(body.getOrDefault("name", ""));
        return ResponseEntity.success(store.deactivateParcel(name));
    }

    @PostMapping("/parcels/remove")
    public ResponseEntity<Object> removeParcel(@RequestBody Map<String, Object> body) {
        String name = body == null ? null : String.valueOf(body.getOrDefault("name", ""));
        return ResponseEntity.success(store.removeParcel(name));
    }

    @PostMapping("/parcels/distribute")
    public ResponseEntity<Object> distributeParcel(@RequestBody Map<String, Object> body) {
        return ResponseEntity.success(store.distributeParcel(body));
    }

    @GetMapping("/identity")
    public ResponseEntity<Object> identity() {
        return ResponseEntity.success(identityService.identityForUi());
    }

    @PutMapping("/identity")
    public ResponseEntity<Object> saveIdentity(@RequestBody Object body) {
        return ResponseEntity.success(store.writeIdentity(body));
    }

    @PostMapping("/identity/save-ldap")
    public ResponseEntity<Object> saveLdap(@RequestBody Map<String, Object> body) {
        return ResponseEntity.success(identityService.saveLdap(body));
    }

    @PostMapping("/identity/test-ldap")
    public ResponseEntity<Object> testLdap(@RequestBody Map<String, Object> body) {
        return ResponseEntity.success(identityService.testLdap(body));
    }

    @GetMapping("/tls")
    public ResponseEntity<Object> tls() {
        return ResponseEntity.success(store.tlsStatus());
    }

    @PostMapping("/tls/init")
    public ResponseEntity<Object> tlsInit() {
        return ResponseEntity.success(identityService.enableTlsHosts());
    }

    @PostMapping("/tls/distribute")
    public ResponseEntity<Object> tlsDistribute(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.success(identityService.distributeTls(body));
    }

    @PostMapping("/tls/disable")
    public ResponseEntity<Object> tlsDisable() {
        return ResponseEntity.success(identityService.disableTls());
    }

    @GetMapping("/login-options")
    public ResponseEntity<Object> loginOptions() {
        return ResponseEntity.success(identityService.loginOptions());
    }

    @PostMapping("/login")
    public ResponseEntity<Object> directoryLogin(@RequestBody Map<String, Object> body) {
        String user = body == null ? null : String.valueOf(body.getOrDefault("username", ""));
        String pass = body == null ? null : String.valueOf(body.getOrDefault("password", ""));
        return ResponseEntity.success(identityService.loginDirectory(user, pass));
    }

    @PostMapping("/identity/enable-directory")
    public ResponseEntity<Object> enableDirectory() {
        return ResponseEntity.success(identityService.enableDirectory());
    }

    @PostMapping("/identity/enable-kdc")
    public ResponseEntity<Object> enableKdc() {
        return ResponseEntity.success(identityService.enableKdc());
    }

    @GetMapping("/identity/kdc")
    public ResponseEntity<Object> kdc() {
        return ResponseEntity.success(identityService.kdcStatus());
    }

    @Operation(summary = "evidence logs", description = "Pull recent service logs via host agents")
    @PostMapping("/evidence/logs")
    public ResponseEntity<Object> evidenceLogs(@RequestBody Map<String, Object> body) {
        return ResponseEntity.success(evidenceService.fetchLogs(body));
    }

    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AuditLogPO> all = auditLogDao.findAll();
        if (all == null) {
            return ResponseEntity.success(rows);
        }
        int from = Math.max(0, all.size() - 100);
        for (int i = all.size() - 1; i >= from; i--) {
            AuditLogPO a = all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("userId", a.getUserId());
            m.put("uri", a.getUri());
            m.put("tag", a.getTagName());
            m.put("summary", a.getOperationSummary());
            m.put("desc", a.getOperationDesc());
            rows.add(m);
        }
        return ResponseEntity.success(rows);
    }
}
