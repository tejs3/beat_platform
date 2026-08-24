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
package org.apache.bigtop.manager.server.controller;

import org.apache.bigtop.manager.server.model.vo.AdvisorySuggestionVO;
import org.apache.bigtop.manager.server.service.AdvisoryService;
import org.apache.bigtop.manager.server.utils.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import java.util.List;

@Tag(name = "Advisory Controller")
@RestController
@RequestMapping("/llm/advisory")
public class AdvisoryController {

    @Resource
    private AdvisoryService advisoryService;

    @Operation(summary = "list suggestions", description = "List AI advisory suggestions (advise only, never auto-fix)")
    @GetMapping("/suggestions")
    public ResponseEntity<List<AdvisorySuggestionVO>> listSuggestions() {
        return ResponseEntity.success(advisoryService.listSuggestions());
    }

    @Operation(summary = "config review", description = "Review one service's configs (advise only)")
    @GetMapping("/config-review/{serviceId}")
    public ResponseEntity<List<AdvisorySuggestionVO>> reviewServiceConfigs(
            @PathVariable("serviceId") Long serviceId) {
        return ResponseEntity.success(advisoryService.reviewServiceConfigs(serviceId));
    }
}
