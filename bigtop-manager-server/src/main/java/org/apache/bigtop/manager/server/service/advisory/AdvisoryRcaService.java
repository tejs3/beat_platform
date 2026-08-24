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

import org.apache.bigtop.manager.ai.assistant.config.GeneralAssistantConfig;
import org.apache.bigtop.manager.ai.core.enums.PlatformType;
import org.apache.bigtop.manager.ai.core.factory.AIAssistant;
import org.apache.bigtop.manager.ai.core.factory.AIAssistantFactory;
import org.apache.bigtop.manager.dao.po.AuthPlatformPO;
import org.apache.bigtop.manager.dao.po.PlatformPO;
import org.apache.bigtop.manager.dao.repository.AuthPlatformDao;
import org.apache.bigtop.manager.dao.repository.PlatformDao;
import org.apache.bigtop.manager.server.enums.AuthPlatformStatus;
import org.apache.bigtop.manager.server.model.converter.AuthPlatformConverter;
import org.apache.bigtop.manager.server.model.dto.AuthPlatformDTO;
import org.apache.bigtop.manager.server.model.vo.AdvisorySuggestionVO;
import org.apache.bigtop.manager.server.service.alephys.AlephysEvidenceService;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Root-cause advisory: pull host logs (SSH/evidence) and ask the configured LLM
 * (OpenAI / ChatGPT etc.) for exact findings + fix steps — not "go read the logs".
 */
@Slf4j
@Service
public class AdvisoryRcaService {

    @Resource
    private AlephysEvidenceService evidenceService;

    @Resource
    private AIAssistantFactory aiAssistantFactory;

    @Resource
    private AuthPlatformDao authPlatformDao;

    @Resource
    private PlatformDao platformDao;

    /**
     * Enrich an unhealthy-service/component card with log evidence + LLM diagnosis.
     * Falls back to a deterministic log-based summary if LLM is not configured.
     */
    public AdvisorySuggestionVO enrichWithLogsAndLlm(
            AdvisorySuggestionVO base, String serviceKey, String hostnameHint) {
        return enrichWithLogsAndLlm(base, serviceKey, hostnameHint, null);
    }

    @SuppressWarnings("unchecked")
    public AdvisorySuggestionVO enrichWithLogsAndLlm(
            AdvisorySuggestionVO base, String serviceKey, String hostnameHint, String configDump) {
        if (base == null) {
            return null;
        }
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("service", normalizeService(serviceKey));
            req.put("lines", 80);
            if (hostnameHint != null && !hostnameHint.isBlank()) {
                req.put("hostname", hostnameHint);
            }
            Map<String, Object> evidence = evidenceService.fetchLogs(req);
            String logExcerpt = compactLogs(evidence, hostnameHint, 6000);
            // Security: never send raw IPs/DNS/secrets to the LLM; also scrub UI-facing excerpts
            String safeLogs = LlmLogRedactor.redact(logExcerpt);
            String safeHost = LlmLogRedactor.redactHostLabel(hostnameHint);
            String safeConfigs = configDump == null || configDump.isBlank()
                    ? ""
                    : LlmLogRedactor.redact(configDump);

            if (safeLogs.isBlank()) {
                base.setSuggestedFix(
                        "Could not pull role logs from hosts (SSH/agent). Check agent reachability, then Start the role from BEAT → service → Components.\n"
                                + "Host: "
                                + safeHost);
                base.setWhyItMatters(base.getWhyItMatters() + " No log evidence available yet.");
                base.setConfidence("Low");
                return base;
            }

            // Known log signatures beat the LLM — models invent Kerberos when the lab is SIMPLE
            if (AdvisoryLogHeuristics.applyIfKnown(base, serviceKey, logExcerpt)) {
                base.setConfidence("High");
                return base;
            }

            String llm = askLlm(base.getProblem(), serviceKey, safeHost, safeLogs, safeConfigs);
            if (llm != null && !llm.isBlank()) {
                applyLlmSections(base, LlmLogRedactor.redact(llm), safeLogs);
                base.setConfidence("High");
                if (looksWrongOrGenericFix(base.getSuggestedFix(), logExcerpt)
                        && AdvisoryLogHeuristics.applyIfKnown(base, serviceKey, logExcerpt)) {
                    base.setConfidence("High");
                }
            } else {
                applyLogOnlySummary(base, safeLogs);
                base.setConfidence("Medium");
            }
            return base;
        } catch (Exception e) {
            log.warn("RCA enrich failed for {}: {}", base.getId(), e.getMessage());
            return base;
        }
    }

    private static boolean looksWrongOrGenericFix(String fix, String logs) {
        if (looksGenericFix(fix)) {
            return true;
        }
        if (fix == null) {
            return true;
        }
        String f = fix.toLowerCase(Locale.ROOT);
        String l = logs == null ? "" : logs.toLowerCase(Locale.ROOT);
        // Never let LLM push Kerberos when the failure is "secure mode without keytab" on a simple lab
        if (l.contains("doesn't have a keytab") || l.contains("does not have a keytab")) {
            if (f.contains("= kerberos") || f.contains("to 'kerberos'") || f.contains("to \"kerberos\"")) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksGenericFix(String fix) {
        if (fix == null || fix.isBlank()) {
            return true;
        }
        String f = fix.toLowerCase(Locale.ROOT);
        return f.contains("configure llm under system")
                || f.contains("address the errors shown in the log excerpt")
                || (f.contains("open configs") && !f.contains("config changes") && !f.contains("["));
    }

    private String askLlm(String problem, String service, String host, String logs, String configs) {
        try {
            AuthPlatformPO auth = findActiveAuth();
            if (auth == null) {
                return null;
            }
            AuthPlatformDTO dto = AuthPlatformConverter.INSTANCE.fromPO2DTO(auth);
            PlatformPO platform = platformDao.findById(auth.getPlatformId());
            if (platform == null || dto.getModel() == null) {
                return null;
            }
            GeneralAssistantConfig config = GeneralAssistantConfig.builder()
                    .setPlatformType(PlatformType.getPlatformType(platform.getName().toLowerCase(Locale.ROOT)))
                    .setModel(dto.getModel())
                    .setId(auth.getId())
                    .setLanguage("en")
                    .addCredentials(dto.getAuthCredentials())
                    .build();
            AIAssistant assistant = aiAssistantFactory.createForTest(config, null);
            String configBlock = (configs == null || configs.isBlank())
                    ? "(no service configs attached)"
                    : configs;
            String prompt =
                    """
                    You are BEAT Manager ops RCA. A cluster role is unhealthy.
                    Do NOT tell the operator to "go read logs" — you already have redacted logs and configs.
                    Hostnames and IPs are redacted as [REDACTED_*]; reason about errors, not network identity.
                    Never invent Cloudera or Bigtop product names — this product is BEAT only.

                    Prefer concrete config fixes when logs/configs support them.
                    CRITICAL: If CURRENT CONFIGS show hadoop.security.authentication=simple or
                    hbase.security.authentication=simple, do NOT recommend Kerberos/keytabs.
                    For "Running in secure mode, but config doesn't have a keytab" on a simple lab,
                    recommend setting authentication back to simple (not enabling kerberos).
                    Reply in exactly this format (plain text, no markdown fences):

                    FOUND:
                    <1-3 concrete lines quoting or paraphrasing the log root cause>

                    CONFIG:
                    <If a config fix is needed: one line per change as: [config-file] property = recommendedValue (was currentValue)>
                    <If not a config issue: none>

                    FIX:
                    <numbered steps. If CONFIG has entries, step 1 must say: In BEAT → service → Configs, set those exact properties, Save/Apply Config, then Restart. Do NOT give only "open Configs and review".>

                    VERIFY:
                    <how to confirm healthy>

                    Problem: %s
                    Service: %s
                    Host: %s

                    CURRENT CONFIGS (redacted):
                    %s

                    LOGS (redacted):
                    %s
                    """
                            .formatted(
                                    problem,
                                    service == null ? "?" : service,
                                    host == null || host.isBlank() ? "host" : host,
                                    configBlock,
                                    logs);
            return assistant.ask(prompt);
        } catch (Exception e) {
            log.warn("LLM RCA ask failed: {}", e.getMessage());
            return null;
        }
    }

    private AuthPlatformPO findActiveAuth() {
        List<AuthPlatformPO> all = authPlatformDao.findAll();
        if (all == null) {
            return null;
        }
        for (AuthPlatformPO p : all) {
            if (p != null && !Boolean.TRUE.equals(p.getIsDeleted()) && AuthPlatformStatus.isActive(p.getStatus())) {
                return p;
            }
        }
        return null;
    }

    private static void applyLlmSections(AdvisorySuggestionVO base, String llm, String logs) {
        String found = section(llm, "FOUND:", "CONFIG:");
        if (found == null || found.isBlank()) {
            found = section(llm, "FOUND:", "FIX:");
        }
        String config = section(llm, "CONFIG:", "FIX:");
        String fix = section(llm, "FIX:", "VERIFY:");
        String verify = section(llm, "VERIFY:", null);
        if (found != null && !found.isBlank()) {
            base.setWhyItMatters("From host logs:\n" + found.trim());
        } else {
            base.setWhyItMatters("From host logs (excerpt):\n" + firstErrorLines(logs, 8));
        }
        StringBuilder fixOut = new StringBuilder();
        if (config != null && !config.isBlank() && !config.trim().equalsIgnoreCase("none")) {
            fixOut.append("CONFIG CHANGES:\n").append(config.trim()).append("\n\n");
        }
        if (fix != null && !fix.isBlank()) {
            fixOut.append(fix.trim());
        }
        if (!fixOut.isEmpty()) {
            base.setSuggestedFix(fixOut.toString().trim());
        }
        if (verify != null && !verify.isBlank()) {
            base.setHowToVerify(verify.trim());
        }
    }

    private static void applyLogOnlySummary(AdvisorySuggestionVO base, String logs) {
        String errs = firstErrorLines(logs, 12);
        base.setWhyItMatters("Log evidence pulled from hosts:\n" + errs);
        base.setSuggestedFix(
                "No known signature matched these logs yet.\n"
                        + "1) Use the error lines above (not a generic restart).\n"
                        + "2) In BEAT → service → Components, Start/Restart only the role named in the error.\n"
                        + "3) If a property is named in the log, set that exact property under Configs, Save + Apply Config, Restart.\n"
                        + "4) Optional: System → LLM Config for richer wording next time.");
        base.setHowToVerify("Component/service status returns to healthy in BEAT.");
    }

    private static String section(String text, String start, String end) {
        if (text == null) {
            return null;
        }
        int i = text.toUpperCase(Locale.ROOT).indexOf(start.toUpperCase(Locale.ROOT));
        if (i < 0) {
            return null;
        }
        int from = i + start.length();
        int to = text.length();
        if (end != null) {
            int j = text.toUpperCase(Locale.ROOT).indexOf(end.toUpperCase(Locale.ROOT), from);
            if (j > from) {
                to = j;
            }
        }
        return text.substring(from, to).trim();
    }

    @SuppressWarnings("unchecked")
    private static String compactLogs(Map<String, Object> evidence, String hostnameHint, int maxChars) {
        if (evidence == null) {
            return "";
        }
        Object hostsObj = evidence.get("hosts");
        if (!(hostsObj instanceof List<?> hosts)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : hosts) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String hn = String.valueOf(m.get("hostname"));
            if (hostnameHint != null
                    && !hostnameHint.isBlank()
                    && hn != null
                    && !hn.equalsIgnoreCase(hostnameHint)
                    && !hostnameHint.toLowerCase(Locale.ROOT).contains(hn.toLowerCase(Locale.ROOT))
                    && !hn.toLowerCase(Locale.ROOT).contains(hostnameHint.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Object excerpt = m.get("excerpt");
            if (excerpt == null || String.valueOf(excerpt).isBlank()) {
                continue;
            }
            sb.append("===== ").append(LlmLogRedactor.redactHostLabel(hn)).append(" =====\n");
            sb.append(excerpt).append("\n");
            if (sb.length() >= maxChars) {
                break;
            }
        }
        if (sb.length() > maxChars) {
            return sb.substring(0, maxChars) + "\n...(truncated)";
        }
        return sb.toString();
    }

    private static String firstErrorLines(String logs, int maxLines) {
        if (logs == null || logs.isBlank()) {
            return "(empty)";
        }
        String[] lines = logs.split("\\R");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("error")
                    || l.contains("exception")
                    || l.contains("fatal")
                    || l.contains("warn")
                    || l.contains("caused by")
                    || l.contains("cannot")
                    || l.contains("failed")) {
                sb.append(line).append("\n");
                n++;
                if (n >= maxLines) {
                    break;
                }
            }
        }
        if (n == 0) {
            int take = Math.min(lines.length, maxLines);
            for (int i = Math.max(0, lines.length - take); i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String normalizeService(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return "hadoop";
        }
        String s = serviceKey.toLowerCase(Locale.ROOT);
        if (s.contains("zookeeper") || s.equals("zk")) {
            return "zookeeper";
        }
        if (s.contains("hive")) {
            return "hive";
        }
        if (s.contains("hbase")) {
            return "hbase";
        }
        if (s.contains("spark")) {
            return "spark";
        }
        if (s.contains("yarn")) {
            return "yarn";
        }
        if (s.contains("prometheus") || s.equals("prom")) {
            return "prometheus";
        }
        if (s.contains("hadoop") || s.contains("hdfs")) {
            return "hadoop";
        }
        return s.replaceAll("[^a-z0-9]+", "");
    }
}
