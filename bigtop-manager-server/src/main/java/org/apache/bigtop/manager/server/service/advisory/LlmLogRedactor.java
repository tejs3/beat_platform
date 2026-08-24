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

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Security redactor for LLM payloads: strip IPs, DNS, emails, secrets, keytabs paths
 * before any log text leaves the BEAT control plane.
 */
public final class LlmLogRedactor {

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");
    private static final Pattern IPV6 = Pattern.compile(
            "\\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\\b|\\b(?:[0-9a-fA-F]{0,4}:){1,7}:\\b");
    private static final Pattern EMAIL =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern URL_HOST =
            Pattern.compile("(?i)\\bhttps?://([^/\\s\"']+)");
    // host.domain.tld or multi-label FQDNs (avoid matching java.lang.Exception)
    private static final Pattern FQDN = Pattern.compile(
            "\\b(?!(?:java|javax|org|com|net|sun|jdk|scala)\\.)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)){1,}\\b");
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|authorization)\\s*[:=]\\s*[^\\s,;\"']+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern KEYTAB = Pattern.compile("(?i)\\S+\\.keytab\\b");
    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN [A-Z ]+-----.*?-----END [A-Z ]+-----", Pattern.DOTALL);

    private LlmLogRedactor() {}

    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return input == null ? "" : input;
        }
        AtomicInteger hostIdx = new AtomicInteger(1);
        String s = input;
        s = PEM_BLOCK.matcher(s).replaceAll("[REDACTED_PEM]");
        s = BEARER.matcher(s).replaceAll("Bearer [REDACTED_TOKEN]");
        s = SECRET_KV.matcher(s).replaceAll("$1=[REDACTED]");
        s = KEYTAB.matcher(s).replaceAll("[REDACTED_KEYTAB]");
        s = EMAIL.matcher(s).replaceAll("[REDACTED_EMAIL]");
        s = IPV4.matcher(s).replaceAll("[REDACTED_IP]");
        s = IPV6.matcher(s).replaceAll("[REDACTED_IP]");
        s = redactUrlHosts(s);
        s = redactFqdns(s, hostIdx);
        return s;
    }

    /** Stable host placeholder for prompts (never send real DNS). */
    public static String redactHostLabel(String host) {
        if (host == null || host.isBlank() || "cluster".equalsIgnoreCase(host) || "(all)".equals(host)) {
            return "host";
        }
        return "host-" + Math.floorMod(host.toLowerCase(Locale.ROOT).hashCode(), 10000);
    }

    private static String redactUrlHosts(String s) {
        Matcher m = URL_HOST.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replace(m.group(1), "[REDACTED_HOST]")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String redactFqdns(String s, AtomicInteger hostIdx) {
        Matcher m = FQDN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            // skip version-like or pure package fragments without a real TLD-ish last label length
            if (!g.contains(".") || looksLikeJavaPackage(g) || looksLikeFileExt(g)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(g));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement("[REDACTED_HOST_" + hostIdx.getAndIncrement() + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean looksLikeJavaPackage(String g) {
        String lower = g.toLowerCase(Locale.ROOT);
        if (lower.startsWith("java.")
                || lower.startsWith("javax.")
                || lower.startsWith("org.apache.")
                || lower.startsWith("org.springframework.")
                || lower.startsWith("com.google.")
                || lower.startsWith("io.netty.")
                || lower.startsWith("scala.")
                || lower.startsWith("jdk.")
                || lower.startsWith("sun.")) {
            return true;
        }
        // FQCN fragments: io.IOException, apache.hadoop.yarn.client… (class segment starts uppercase)
        String[] parts = g.split("\\.");
        for (String p : parts) {
            if (!p.isEmpty() && Character.isUpperCase(p.charAt(0))) {
                return true;
            }
        }
        // common package roots that appear mid-stack-trace without java./org. prefix after partial redact
        if (parts.length >= 2) {
            String a = parts[0].toLowerCase(Locale.ROOT);
            if (a.equals("io")
                    || a.equals("lang")
                    || a.equals("util")
                    || a.equals("apache")
                    || a.equals("hadoop")
                    || a.equals("hbase")
                    || a.equals("yarn")
                    || a.equals("zookeeper")
                    || a.equals("netty")
                    || a.equals("fasterxml")) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeFileExt(String g) {
        // e.g. file.log / zoo.cfg — single short last label that is a known extension
        int dot = g.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = g.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("log|out|txt|xml|cfg|conf|properties|json|yml|yaml|sh|jar|so|class|java");
    }
}
