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
package org.apache.bigtop.manager.server.service.alephys;

import org.apache.bigtop.manager.common.utils.JsonUtils;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AlephysStore {

    public static final Path ROOT = Path.of(
            System.getProperty("beat.home", System.getProperty("alephys.home", "/opt/beat-manager/var/beat")));
    /** Legacy agent/platform packages — not shown in Parcels UI */
    public static final Path AGENT_REPO = Path.of(
            System.getProperty("beat.agentRepo", "/opt/beat-manager/ui/repo"));
    public static final Path TLS_DIR = ROOT.resolve("tls");
    public static final Path ESTATES = ROOT.resolve("estates.json");
    public static final Path IDENTITY = ROOT.resolve("identity.json");

    public void ensureDefaults() {
        try {
            Files.createDirectories(ROOT);
            Files.createDirectories(TLS_DIR);
            if (!Files.exists(ESTATES)) {
                writeJson(ESTATES, defaultEstates());
            }
            if (!Files.exists(IDENTITY)) {
                writeJson(IDENTITY, defaultIdentity());
            }
            if (!Files.exists(PROCESSES)) {
                writeJson(PROCESSES, emptyProcesses());
            }
            if (!Files.exists(PARCEL_STATE)) {
                writeJson(PARCEL_STATE, defaultParcelState());
            }
        } catch (Exception ignored) {
            // first request retries
        }
    }

    public Object readEstates() {
        ensureDefaults();
        return readJson(ESTATES, defaultEstates());
    }

    public Object writeEstates(Object body) {
        ensureDefaults();
        writeJson(ESTATES, body);
        return readEstates();
    }

    public Object readIdentity() {
        ensureDefaults();
        return readJson(IDENTITY, defaultIdentity());
    }

    public Object writeIdentity(Object body) {
        ensureDefaults();
        writeJson(IDENTITY, body);
        return readIdentity();
    }

    public static final Path PARCEL_ROOT = Path.of(
            System.getProperty("beat.parcels", System.getProperty("alephys.parcels", "/opt/beat/parcels")));
    public static final Path PARCEL_STATE = ROOT.resolve("parcels.json");
    public static final String DEFAULT_PARCEL_REPO_URL =
            System.getProperty(
                    "beat.parcelRepoUrl",
                    System.getProperty("alephys.parcelRepoUrl", "https://github.com/tejs3/beat-repo3.0.0-1"));

    public List<Map<String, Object>> listParcels() {
        ensureDefaults();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> state = readParcelState();
        String repoUrl = String.valueOf(state.getOrDefault("repoUrl", DEFAULT_PARCEL_REPO_URL));
        String activeName = String.valueOf(state.getOrDefault("activeParcel", ""));

        // Always list local BEAT runtime parcels (repo URL is for catalog/download metadata)
        Path repoDir = PARCEL_ROOT.resolve("repo");
        if (!Files.isDirectory(repoDir)) {
            try {
                Files.createDirectories(repoDir);
            } catch (IOException ignored) {
                return out;
            }
        }
        scanParcelDir(repoDir, repoUrl, activeName, out);
        if (Files.isDirectory(AGENT_REPO)) {
            scanParcelDir(AGENT_REPO, repoUrl, activeName, out);
        }
        scanUnpackedParcels(repoUrl, activeName, out);

        Path activeLink = PARCEL_ROOT.resolve("BEAT");
        boolean fsActive = Files.isSymbolicLink(activeLink) || Files.isDirectory(activeLink);
        for (Map<String, Object> row : out) {
            String name = String.valueOf(row.get("name"));
            boolean activated = name.equals(activeName);
            if (activeName.isBlank() && fsActive && name.startsWith("BEAT-") && name.endsWith(".parcel")) {
                activated = true;
            }
            row.put("activated", activated);
            row.put("parcelRoot", PARCEL_ROOT.toString());
            row.put("activeSymlink", activeLink.toString());
            if (!isValidParcelRepoUrl(repoUrl)) {
                row.put("repoUrlWarning", "Saved repository URL is invalid — fix URL and Save again");
            }
        }
        return out;
    }

    /** Services available from the active BEAT parcel (Add Service uses this list). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listParcelServices() {
        ensureDefaults();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> state = readParcelState();
        String active = String.valueOf(state.getOrDefault("activeParcel", ""));
        Path activeRoot = PARCEL_ROOT.resolve("BEAT");
        if (!Files.isDirectory(activeRoot) && !Files.isSymbolicLink(activeRoot)) {
            return out;
        }
        try {
            if (Files.isSymbolicLink(activeRoot)) {
                activeRoot = PARCEL_ROOT.resolve(Files.readSymbolicLink(activeRoot));
            }
        } catch (IOException ignored) {
            // use BEAT path as-is
        }
        Path meta = activeRoot.resolve("meta").resolve("parcel.json");
        if (Files.isRegularFile(meta)) {
            Object raw = readJson(meta, Map.of());
            if (raw instanceof Map<?, ?> m) {
                Object comps = m.get("components");
                if (comps instanceof List<?> list) {
                    for (Object c : list) {
                        if (c instanceof Map<?, ?> cm) {
                            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) cm);
                            if (!row.containsKey("service") && row.containsKey("name")) {
                                String n = String.valueOf(row.get("name"));
                                row.put("service", mapComponentToService(n));
                            }
                            row.putIfAbsent("displayName", row.get("service"));
                            out.add(row);
                        } else if (c instanceof String s) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", s);
                            row.put("service", mapComponentToService(s));
                            row.put("displayName", row.get("service"));
                            out.add(row);
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            // fallback catalog from repo URL (GitHub raw)
            String repoUrl = String.valueOf(state.getOrDefault("repoUrl", DEFAULT_PARCEL_REPO_URL));
            Object catalog = fetchRemoteCatalog(repoUrl);
            if (catalog instanceof Map<?, ?> cat) {
                Object comps = cat.get("components");
                if (comps instanceof List<?> list) {
                    for (Object c : list) {
                        if (c instanceof Map<?, ?> cm) {
                            out.add(new LinkedHashMap<>((Map<String, Object>) cm));
                        }
                    }
                }
            }
        }
        if (out.isEmpty() && !active.isBlank()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "BEAT");
            row.put("service", "beat");
            row.put("displayName", "BEAT Runtime");
            row.put("version", active.replace(".parcel", ""));
            out.add(row);
        }
        return dedupeParcelServices(out);
    }

    /** One UI row per installable service (no duplicate hadoop/ranger roles). */
    private List<Map<String, Object>> dedupeParcelServices(List<Map<String, Object>> raw) {
        LinkedHashMap<String, Map<String, Object>> byService = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            String service = String.valueOf(row.getOrDefault("service", row.getOrDefault("name", ""))).toLowerCase();
            if (service.isBlank()) {
                continue;
            }
            // Skip mapreduce as its own tile when hadoop/hdfs/yarn exist
            if ("hadoop".equals(service) && byService.containsKey("hdfs")) {
                continue;
            }
            if ("mapreduce".equals(service)) {
                service = "hadoop";
            }
            Map<String, Object> existing = byService.get(service);
            if (existing == null) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.put("service", service);
                copy.putIfAbsent("displayName", displayNameForService(service));
                byService.put(service, copy);
            } else {
                // Prefer row with explicit displayName / version
                if (row.get("version") != null && existing.get("version") == null) {
                    existing.put("version", row.get("version"));
                }
            }
        }
        return new ArrayList<>(byService.values());
    }

    private static String displayNameForService(String service) {
        return switch (service) {
            case "hdfs" -> "HDFS";
            case "yarn" -> "YARN";
            case "hbase" -> "HBase";
            case "hive" -> "Hive";
            case "zookeeper" -> "ZooKeeper";
            case "kafka" -> "Kafka";
            case "knox" -> "Knox";
            case "ozone" -> "Ozone";
            case "polaris" -> "Polaris";
            case "ranger" -> "Ranger";
            case "iceberg" -> "Iceberg";
            case "flink" -> "Flink";
            case "solr" -> "Solr";
            case "spark" -> "Spark";
            case "hadoop" -> "Hadoop";
            case "nifi" -> "NiFi";
            default -> service.substring(0, 1).toUpperCase() + service.substring(1);
        };
    }

    private static String mapComponentToService(String component) {
        if (component == null) {
            return "";
        }
        return switch (component) {
            case "hadoop-hdfs" -> "hdfs";
            case "hadoop-yarn" -> "yarn";
            case "hadoop", "hadoop-mapreduce" -> "hadoop";
            case "ranger-admin", "ranger-usersync" -> "ranger";
            default -> component.contains("-") && !component.startsWith("hadoop")
                    ? component.split("-")[0]
                    : component.replace("hadoop-", "");
        };
    }

    private void scanUnpackedParcels(String repoUrl, String activeName, List<Map<String, Object>> out) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PARCEL_ROOT)) {
            for (Path p : stream) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                String dirName = p.getFileName().toString();
                if (!dirName.startsWith("BEAT-")) {
                    continue;
                }
                String name = dirName + ".parcel";
                boolean already = out.stream().anyMatch(r -> name.equals(String.valueOf(r.get("name"))));
                if (already) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("bytes", dirSize(p));
                row.put("sha256", "(unpacked on disk)");
                row.put("activated", name.equals(activeName));
                row.put("repoUrl", repoUrl);
                row.put("baseUrl", joinUrl(repoUrl, name));
                row.put("localPath", p.toString());
                row.put("unpacked", true);
                out.add(row);
            }
        } catch (Exception ignored) {
            // empty
        }
    }

    private static long dirSize(Path root) {
        try {
            return Files.walk(root).filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void scanParcelDir(
            Path dir, String repoUrl, String activeName, List<Map<String, Object>> out) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p) && !Files.isSymbolicLink(p)) {
                    continue;
                }
                if (!Files.exists(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                // Parcels UI: BEAT runtime .parcel only (no agent tarballs or legacy product tars)
                if (!name.endsWith(".parcel") || !name.startsWith("BEAT-")) {
                    continue;
                }
                if (name.contains("beat-agent") || name.contains("bigtop")) {
                    continue;
                }
                boolean already = out.stream().anyMatch(r -> name.equals(String.valueOf(r.get("name"))));
                if (already) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                long bytes = Files.size(p);
                row.put("name", name);
                row.put("bytes", bytes);
                Path shaFile = Path.of(p.toString() + ".sha256");
                if (Files.isRegularFile(shaFile)) {
                    row.put("sha256", Files.readString(shaFile).trim());
                } else {
                    row.put("sha256", bytes > 8L * 1024 * 1024 ? "(omitted for large parcel)" : sha256(p));
                }
                row.put("activated", name.equals(activeName));
                row.put("repoUrl", repoUrl);
                row.put("baseUrl", joinUrl(repoUrl, name));
                row.put("localPath", p.toString());
                out.add(row);
            }
        } catch (Exception ignored) {
            // empty
        }
    }

    private static String joinUrl(String base, String name) {
        if (base == null || base.isBlank()) {
            return name;
        }
        String b = base.trim();
        if (b.contains("github.com") && b.contains("beat-repo")) {
            String repo = b.replaceAll("/+$", "");
            if (!repo.endsWith(".git")) {
                repo = repo.replaceAll("/tree/.*", "").replaceAll("/releases/.*", "");
            }
            String slug = repo.contains("github.com/") ? repo.substring(repo.indexOf("github.com/") + 11) : repo;
            slug = slug.replace(".git", "");
            return "https://github.com/" + slug + "/releases/latest/download/" + name;
        }
        String prefix = b.endsWith("/") ? b : b + "/";
        return prefix + name;
    }

    private static boolean isValidParcelRepoUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String u = url.trim().toLowerCase();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return false;
        }
        if (u.contains("github.com")) {
            return u.contains("tejs3/beat-repo");
        }
        // lab HTTP mirror
        return u.contains("/ui/repo") || u.contains("10.1.0.191");
    }

    private static Object fetchRemoteCatalog(String repoUrl) {
        try {
            String raw = repoUrl.trim().replaceAll("/+$", "");
            String catalogUrl;
            if (raw.contains("github.com") && raw.contains("beat-repo")) {
                String slug = raw.contains("github.com/") ? raw.substring(raw.indexOf("github.com/") + 11) : raw;
                slug = slug.replace(".git", "");
                if (slug.contains("/tree/")) {
                    slug = slug.substring(0, slug.indexOf("/tree/"));
                }
                catalogUrl = "https://raw.githubusercontent.com/" + slug + "/main/catalog.json";
            } else {
                catalogUrl = raw + "/catalog.json";
            }
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(catalogUrl))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp =
                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return JsonUtils.readFromString(resp.body(), Object.class);
            }
        } catch (Exception ignored) {
            // catalog optional
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readParcelState() {
        ensureDefaults();
        Object raw = readJson(PARCEL_STATE, defaultParcelState());
        if (raw instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return defaultParcelState();
    }

    public Map<String, Object> setParcelRepoUrl(String url) {
        Map<String, Object> state = readParcelState();
        if (url == null || url.isBlank()) {
            state.put("repoError", "Parcel repository URL is required");
            state.put("ok", false);
            writeJson(PARCEL_STATE, state);
            return state;
        }
        String trimmed = url.trim();
        if (!isValidParcelRepoUrl(trimmed)) {
            state.put("repoUrl", trimmed);
            state.put(
                    "repoError",
                    "Invalid BEAT parcel repo. Example: https://github.com/tejs3/beat-repo3.0.0-1");
            state.put("ok", false);
            state.put("updatedAt", Instant.now().toString());
            writeJson(PARCEL_STATE, state);
            return state;
        }
        state.put("repoUrl", trimmed);
        state.remove("repoError");
        state.put("ok", true);
        state.put("updatedAt", Instant.now().toString());
        writeJson(PARCEL_STATE, state);
        return state;
    }

    /**
     * Activate a local BEAT-*.parcel (CM-style): unpack under /opt/beat/parcels and point BEAT symlink.
     * Distribute-to-agents is recorded as pending status for Phase 4 host fan-out.
     */
    public Map<String, Object> activateParcel(String parcelName) {
        ensureDefaults();
        if (parcelName == null || parcelName.isBlank()) {
            throw new IllegalArgumentException("parcelName required");
        }
        Path parcelFile = resolveParcelFile(parcelName);
        String versionDir = parcelName.replace(".parcel", "");
        Path dest = PARCEL_ROOT.resolve(versionDir);
        if (parcelFile == null && !Files.isDirectory(dest)) {
            throw new IllegalArgumentException("Parcel not found: " + parcelName);
        }
        try {
            Files.createDirectories(PARCEL_ROOT);
            if (parcelFile != null && !Files.isDirectory(dest)) {
                Files.createDirectories(dest);
                Process p = new ProcessBuilder("tar", "-xzf", parcelFile.toString(), "-C", PARCEL_ROOT.toString())
                        .redirectErrorStream(true)
                        .start();
                String out = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                if (code != 0) {
                    throw new IllegalStateException("tar extract failed: " + out);
                }
            }
            if (!Files.isDirectory(dest)) {
                throw new IllegalStateException("Parcel directory missing after extract: " + dest);
            }
            Path link = PARCEL_ROOT.resolve("BEAT");
            if (Files.exists(link) || Files.isSymbolicLink(link)) {
                Files.delete(link);
            }
            Files.createSymbolicLink(link, Path.of(versionDir));

            Map<String, Object> state = readParcelState();
            state.put("activeParcel", parcelName);
            state.put("activeDir", dest.toString());
            state.put("activatedAt", Instant.now().toString());
            state.put("status", "activated_local");
            state.put(
                    "note",
                    "Activated on manager. Distribute to agents is next (hosts unpack same parcel + BEAT symlink).");
            writeJson(PARCEL_STATE, state);

            Map<String, Object> result = new LinkedHashMap<>(state);
            result.put("ok", true);
            result.put("parcel", parcelName);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("activate failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> deactivateParcel(String parcelName) {
        ensureDefaults();
        try {
            Path link = PARCEL_ROOT.resolve("BEAT");
            if (Files.exists(link) || Files.isSymbolicLink(link)) {
                Files.delete(link);
            }
            Map<String, Object> state = readParcelState();
            String active = String.valueOf(state.getOrDefault("activeParcel", ""));
            if (parcelName == null || parcelName.isBlank() || parcelName.equals(active)) {
                state.put("activeParcel", "");
                state.put("deactivatedAt", Instant.now().toString());
                state.put("status", "deactivated");
            }
            writeJson(PARCEL_STATE, state);
            Map<String, Object> result = new LinkedHashMap<>(state);
            result.put("ok", true);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("deactivate failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> removeParcel(String parcelName) {
        ensureDefaults();
        if (parcelName == null || parcelName.isBlank()) {
            throw new IllegalArgumentException("parcelName required");
        }
        Map<String, Object> state = readParcelState();
        String active = String.valueOf(state.getOrDefault("activeParcel", ""));
        if (parcelName.equals(active)) {
            throw new IllegalStateException("Deactivate parcel before removing: " + parcelName);
        }
        String versionDir = parcelName.replace(".parcel", "");
        Path unpacked = PARCEL_ROOT.resolve(versionDir);
        Path parcelFile = resolveParcelFile(parcelName);
        if (parcelFile == null && !Files.isDirectory(unpacked)) {
            throw new IllegalArgumentException("Parcel not found: " + parcelName);
        }
        try {
            if (parcelFile != null) {
                Files.deleteIfExists(parcelFile);
                Files.deleteIfExists(Path.of(parcelFile.toString() + ".sha256"));
            }
            if (Files.isDirectory(unpacked)) {
                deleteRecursive(unpacked);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("removed", parcelName);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("remove failed: " + e.getMessage(), e);
        }
    }

    /**
     * Rsync active parcel directory to cluster hosts and set BEAT symlink on each agent.
     * Downloads the .parcel on the manager first when only a remote catalog entry exists.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> distributeParcel(Map<String, Object> body) {
        ensureDefaults();
        Map<String, Object> state = readParcelState();
        String parcelName = body == null ? "" : String.valueOf(body.getOrDefault("parcelName", ""));
        if (parcelName.isBlank()) {
            parcelName = String.valueOf(state.getOrDefault("activeParcel", ""));
        }
        if (parcelName.isBlank()) {
            throw new IllegalStateException("Activate a parcel before distributing");
        }
        ensureParcelOnManager(parcelName);

        List<String> hostnames = new ArrayList<>();
        if (body != null && body.get("hostnames") instanceof List<?> list) {
            for (Object h : list) {
                if (h != null) {
                    String hn = String.valueOf(h).trim();
                    if (!hn.isBlank()) {
                        hostnames.add(hn);
                    }
                }
            }
        }
        if (hostnames.isEmpty()) {
            throw new IllegalArgumentException("hostnames required");
        }

        String versionDir = parcelName.replace(".parcel", "");
        Path sourceDir = PARCEL_ROOT.resolve(versionDir);
        if (!Files.isDirectory(sourceDir)) {
            throw new IllegalStateException("Parcel directory missing on manager: " + sourceDir);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String hostname : hostnames) {
            rows.add(pushParcelToHost(hostname, sourceDir, versionDir));
        }
        boolean allOk = rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("ok")));
        state.put("activeParcel", parcelName);
        state.put("distributedAt", Instant.now().toString());
        state.put("distributedHosts", hostnames);
        state.put("status", allOk ? "distributed" : "distribute_partial");
        writeJson(PARCEL_STATE, state);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", allOk);
        result.put("parcel", parcelName);
        result.put("hosts", rows);
        return result;
    }

    private void ensureParcelOnManager(String parcelName) {
        String versionDir = parcelName.replace(".parcel", "");
        Path dest = PARCEL_ROOT.resolve(versionDir);
        if (Files.isDirectory(dest)) {
            return;
        }
        Path parcelFile = resolveParcelFile(parcelName);
        if (parcelFile == null) {
            parcelFile = downloadParcelFile(parcelName);
        }
        if (parcelFile != null && !Files.isDirectory(dest)) {
            activateParcel(parcelName);
        }
    }

    private Path downloadParcelFile(String parcelName) {
        Map<String, Object> state = readParcelState();
        String repoUrl = String.valueOf(state.getOrDefault("repoUrl", DEFAULT_PARCEL_REPO_URL));
        String downloadUrl = joinUrl(repoUrl, parcelName);
        try {
            Files.createDirectories(PARCEL_ROOT.resolve("repo"));
            Path target = PARCEL_ROOT.resolve("repo").resolve(parcelName);
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(downloadUrl))
                    .GET()
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> resp =
                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }
            try (java.io.InputStream in = resp.body()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(target) > 0) {
                return target;
            }
            Files.deleteIfExists(target);
        } catch (Exception ignored) {
            // optional download
        }
        return null;
    }

    private Map<String, Object> pushParcelToHost(String hostname, Path sourceDir, String versionDir) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("hostname", hostname);
        row.put("at", Instant.now().toString());
        try {
            String target = hostname.trim();
            String remoteRoot = PARCEL_ROOT.toString();
            if (remoteRoot == null || remoteRoot.isBlank()) {
                remoteRoot = "/opt/beat/parcels";
            }
            if (isLocalHost(target)) {
                Files.createDirectories(PARCEL_ROOT);
                Path link = PARCEL_ROOT.resolve("BEAT");
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, Path.of(versionDir));
                row.put("ok", true);
                row.put("message", "local");
                return row;
            }
            runSsh(target, "mkdir -p " + shellQuote(remoteRoot + "/" + versionDir), 30);
            String copyMsg = copyParcelToHost(target, sourceDir, remoteRoot, versionDir);
            String linkCmd =
                    "ln -sfn " + shellQuote(versionDir) + " " + shellQuote(remoteRoot + "/BEAT");
            runSsh(target, linkCmd, 30);
            row.put("ok", true);
            row.put("message", copyMsg);
        } catch (Exception e) {
            row.put("ok", false);
            row.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return row;
    }

    private static String copyParcelToHost(String target, Path sourceDir, String remoteRoot, String versionDir)
            throws Exception {
        try {
            Process rsync = new ProcessBuilder(
                            "rsync",
                            "-az",
                            "--delete",
                            sourceDir.toString() + "/",
                            "root@" + target + ":" + remoteRoot + "/" + versionDir + "/")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = rsync.waitFor(600, TimeUnit.SECONDS);
            String rsyncOut = new String(rsync.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (finished && rsync.exitValue() == 0) {
                return "distributed";
            }
        } catch (IOException ignored) {
            // rsync not installed on the manager — fall through to tar|ssh
        }
        String dest = remoteRoot + "/" + versionDir;
        String pipeline = "tar -C "
                + shellQuote(sourceDir.toString())
                + " -cf - . | ssh -o StrictHostKeyChecking=no -o ConnectTimeout=8 root@"
                + target
                + " tar -C "
                + shellQuote(dest)
                + " -xf -";
        Process tar = new ProcessBuilder("bash", "-lc", pipeline).redirectErrorStream(true).start();
        boolean finished = tar.waitFor(600, TimeUnit.SECONDS);
        String out = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished || tar.exitValue() != 0) {
            throw new IllegalStateException(out.isBlank() ? "tar copy failed" : out.trim());
        }
        return "distributed (tar)";
    }

    private static boolean isLocalHost(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }
        String h = hostname.trim();
        if ("localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "::1".equals(h)) {
            return true;
        }
        try {
            java.net.InetAddress target = java.net.InetAddress.getByName(h);
            if (target.isAnyLocalAddress() || target.isLoopbackAddress()) {
                return true;
            }
            java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    if (addrs.nextElement().equals(target)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return false;
    }

    private static void runSsh(String target, String cmd, int timeoutSec) throws Exception {
        // Pass the remote command as one ssh argument so OpenSSH does not
        // word-split `bash -lc mkdir ...` into `mkdir` with no path.
        Process p = new ProcessBuilder(
                        "ssh",
                        "-o",
                        "StrictHostKeyChecking=no",
                        "-o",
                        "ConnectTimeout=8",
                        "root@" + target,
                        cmd)
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("ssh timeout for " + target);
        }
        if (p.exitValue() != 0) {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException(out.isBlank() ? "ssh failed" : out.trim());
        }
    }

    private static String shellQuote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private Path resolveParcelFile(String parcelName) {
        for (Path base : List.of(PARCEL_ROOT.resolve("repo"), AGENT_REPO)) {
            Path candidate = base.resolve(parcelName);
            try {
                if (!Files.exists(candidate) || Files.isDirectory(candidate)) {
                    continue;
                }
                long size = Files.size(candidate);
                if (size > 0) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // skip unreadable file
            }
        }
        return null;
    }

    private static Map<String, Object> defaultParcelState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repoUrl", DEFAULT_PARCEL_REPO_URL);
        m.put("activeParcel", "");
        m.put("status", "idle");
        return m;
    }

    public static final Path TLS_DISTRIBUTE = TLS_DIR.resolve("distribute.json");
    public static final Path TLS_WIZARD = TLS_DIR.resolve("wizard.json");
    public static final Path PROCESSES = ROOT.resolve("processes.json");
    public static final String AGENT_TLS_DIR = "/etc/beat/tls";
    public static final String AGENT_PROCESS_ROOT = "/var/run/beat-agent/process";

    public Map<String, Object> tlsStatus() {
        ensureDefaults();
        Map<String, Object> m = new LinkedHashMap<>();
        Path crt = TLS_DIR.resolve("ca.crt");
        m.put("caPath", crt.toString());
        m.put("present", Files.isRegularFile(crt));
        m.put("notAfter", readNotAfter(crt));
        boolean kdc = false;
        try {
            kdc = Files.isRegularFile(AlephysIdentityService.KDC_STATUS);
        } catch (Exception ignored) {
            // ignore
        }
        m.put("kerberosEnabled", false);
        m.put("kdcConfigured", kdc);
        m.put("hostCertsDir", TLS_DIR.resolve("hosts").toString());
        m.put("agentTlsDir", AGENT_TLS_DIR);
        m.put("issuedHosts", listIssuedHostnames());
        m.put("hosts", readDistributeHosts());
        m.put("wizard", readTlsWizard());
        boolean managerHttps = org.apache.bigtop.manager.server.config.BeatHttpsConnectorConfig.isManagerHttpsEnabled();
        m.put("managerHttpsEnabled", managerHttps);
        m.put("httpDisabled", managerHttps);
        m.put("httpUrl", managerHttps ? null : "http://10.1.0.191:8080/ui/");
        m.put("httpsUrl", managerHttps ? "https://10.1.0.191:8083/ui/" : null);
        m.put(
                "note",
                managerHttps
                        ? "AutoTLS ON: use https://10.1.0.191:8083/ui/ — plain HTTP :8080 is disabled. Disable AutoTLS to restore HTTP."
                        : "AutoTLS: Enable from System → Security wizard. Issues CA + host PEMs on the manager, then agents write "
                                + AGENT_TLS_DIR
                                + ". Hadoop/Hive/Spark stay SIMPLE until you opt into service TLS and Apply Config.");
        return m;
    }

    public Object readTlsWizard() {
        if (!Files.isRegularFile(TLS_WIZARD)) {
            return Map.of();
        }
        return readJson(TLS_WIZARD, Map.of());
    }

    public void writeTlsWizard(Object body) {
        ensureDefaults();
        writeJson(TLS_WIZARD, body);
    }

    public List<String> listIssuedHostnames() {
        List<String> names = new ArrayList<>();
        Path hosts = TLS_DIR.resolve("hosts");
        if (!Files.isDirectory(hosts)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(hosts, "*.crt")) {
            for (Path p : stream) {
                String n = p.getFileName().toString();
                if (n.endsWith(".crt")) {
                    names.add(n.substring(0, n.length() - 4));
                }
            }
        } catch (Exception ignored) {
            // empty
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readDistributeHosts() {
        if (!Files.isRegularFile(TLS_DISTRIBUTE)) {
            return new ArrayList<>();
        }
        try {
            Object raw = JsonUtils.readFromString(Files.readString(TLS_DISTRIBUTE), Object.class);
            if (raw instanceof Map<?, ?> map) {
                Object hosts = map.get("hosts");
                if (hosts instanceof List<?> list) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> row) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            row.forEach((k, v) -> m.put(String.valueOf(k), v));
                            out.add(m);
                        }
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {
            // empty
        }
        return new ArrayList<>();
    }

    public void writeDistributeStatus(List<Map<String, Object>> hosts) {
        ensureDefaults();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updatedAt", Instant.now().toString());
        body.put("agentTlsDir", AGENT_TLS_DIR);
        body.put("hosts", hosts);
        writeJson(TLS_DISTRIBUTE, body);
    }

    public Map<String, Object> initTls() {
        ensureDefaults();
        Path key = TLS_DIR.resolve("ca.key");
        Path crt = TLS_DIR.resolve("ca.crt");
        try {
            if (!Files.exists(crt) || !Files.exists(key)) {
                Process p = new ProcessBuilder(
                                "openssl",
                                "req",
                                "-x509",
                                "-newkey",
                                "rsa:2048",
                                "-days",
                                "365",
                                "-nodes",
                                "-subj",
                                "/CN=BEAT-Lab-CA/O=BEAT",
                                "-keyout",
                                key.toString(),
                                "-out",
                                crt.toString())
                        .redirectErrorStream(true)
                        .start();
                p.waitFor();
            }
        } catch (Exception ignored) {
            // openssl missing — status still reports present=false
        }
        return tlsStatus();
    }

    /**
     * Record a CM-style process id per <b>component/role</b> (e.g. yarn_resourcemanager),
     * not one row per service. Also keeps byService as "latest for service" convenience.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> recordProcess(
            Long clusterId, Long serviceId, String serviceName, String componentName, String processId) {
        ensureDefaults();
        if (processId == null || processId.isBlank()) {
            return Map.of();
        }
        long generation = parseGeneration(processId);
        Map<String, Object> root = (Map<String, Object>) readJson(PROCESSES, emptyProcesses());
        Map<String, Object> byService =
                (Map<String, Object>) root.computeIfAbsent("byService", k -> new LinkedHashMap<>());
        Map<String, Object> byComponent =
                (Map<String, Object>) root.computeIfAbsent("byComponent", k -> new LinkedHashMap<>());
        Map<String, Object> byGeneration =
                (Map<String, Object>) root.computeIfAbsent("byGeneration", k -> new LinkedHashMap<>());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("clusterId", clusterId);
        row.put("serviceId", serviceId);
        row.put("serviceName", serviceName);
        row.put("componentName", componentName);
        row.put("processId", processId);
        row.put("processGeneration", generation);
        row.put("processDir", AGENT_PROCESS_ROOT + "/" + processId);
        row.put("level", "component");
        row.put("updatedAt", Instant.now().toString());

        String serviceKey = clusterId + ":" + serviceName;
        String componentKey = clusterId + ":" + serviceName + ":" + componentName;
        byService.put(serviceKey, row);
        byComponent.put(componentKey, row);
        byGeneration.put(String.valueOf(generation), row);
        byGeneration.put(processId, row);
        writeJson(PROCESSES, root);
        return row;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> latestProcessForComponent(
            Long clusterId, String serviceName, String componentName) {
        ensureDefaults();
        Map<String, Object> root = (Map<String, Object>) readJson(PROCESSES, emptyProcesses());
        Map<String, Object> byComponent =
                (Map<String, Object>) root.getOrDefault("byComponent", Map.of());
        Object row = byComponent.get(clusterId + ":" + serviceName + ":" + componentName);
        if (row instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    /** All recorded process dirs for roles under one service (one map per component/role). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listProcessesForService(Long clusterId, String serviceName) {
        ensureDefaults();
        List<Map<String, Object>> out = new ArrayList<>();
        if (clusterId == null || serviceName == null || serviceName.isBlank()) {
            return out;
        }
        String prefix = clusterId + ":" + serviceName + ":";
        Map<String, Object> root = (Map<String, Object>) readJson(PROCESSES, emptyProcesses());
        Map<String, Object> byComponent =
                (Map<String, Object>) root.getOrDefault("byComponent", Map.of());
        for (Map.Entry<String, Object> e : byComponent.entrySet()) {
            if (!e.getKey().startsWith(prefix) || !(e.getValue() instanceof Map<?, ?> m)) {
                continue;
            }
            out.add(new LinkedHashMap<>((Map<String, Object>) m));
        }
        out.sort((a, b) -> String.valueOf(a.getOrDefault("componentName", ""))
                .compareToIgnoreCase(String.valueOf(b.getOrDefault("componentName", ""))));
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> latestProcessForService(Long clusterId, String serviceName) {
        ensureDefaults();
        Map<String, Object> root = (Map<String, Object>) readJson(PROCESSES, emptyProcesses());
        Map<String, Object> byService = (Map<String, Object>) root.getOrDefault("byService", Map.of());
        Object row = byService.get(clusterId + ":" + serviceName);
        if (row instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> latestProcessForServiceId(Long clusterId, Long serviceId) {
        ensureDefaults();
        Map<String, Object> root = (Map<String, Object>) readJson(PROCESSES, emptyProcesses());
        Map<String, Object> byService = (Map<String, Object>) root.getOrDefault("byService", Map.of());
        for (Object v : byService.values()) {
            if (!(v instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) m;
            Object sid = row.get("serviceId");
            Object cid = row.get("clusterId");
            if (sid != null
                    && cid != null
                    && Long.parseLong(String.valueOf(sid)) == serviceId
                    && Long.parseLong(String.valueOf(cid)) == clusterId) {
                return new LinkedHashMap<>(row);
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveProcess(String processKey) {
        ensureDefaults();
        Map<String, Object> root = (Map<String, Object>) readJson(PROCESSES, emptyProcesses());
        Map<String, Object> byGeneration = (Map<String, Object>) root.getOrDefault("byGeneration", Map.of());
        Object row = byGeneration.get(processKey);
        if (row instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    private static Map<String, Object> emptyProcesses() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byService", new LinkedHashMap<>());
        m.put("byComponent", new LinkedHashMap<>());
        m.put("byGeneration", new LinkedHashMap<>());
        return m;
    }

    private static long parseGeneration(String processId) {
        try {
            int dash = processId.indexOf('-');
            String head = dash > 0 ? processId.substring(0, dash) : processId;
            return Long.parseLong(head);
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000;
        }
    }

    private static List<Map<String, Object>> defaultEstates() {
        return List.of(
                estate("gao", "GAO tenant namespace on the lab lakehouse"),
                estate("renault", "Renault tenant namespace on the lab lakehouse"),
                estate(
                        "polaris",
                        "Polaris tenant namespace. Apache Polaris catalog skipped — HMS is enough for v0."));
    }

    private static Map<String, Object> estate(String name, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", "tenant");
        m.put("hdfsPath", "/warehouse/tenants/" + name);
        m.put("quotaGb", 50);
        m.put("engines", List.of("hive", "spark"));
        m.put("note", note);
        return m;
    }

    private static Map<String, Object> defaultIdentity() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loginMode", "local");
        m.put("ldapUrl", "");
        m.put("bindDn", "");
        m.put("roles", List.of("admin", "operator", "viewer"));
        m.put(
                "loginNote",
                "Login stays local break-glass (admin). Bind LDAP under System → Security when ready.");
        m.put("clusterUsers", List.of("hadoop", "yarn", "hive", "spark", "zookeeper"));
        m.put("externalTenants", List.of("gao", "renault", "polaris"));
        m.put(
                "planesNote",
                "Login users != cluster principals != external analysts. Do not collapse these.");
        return m;
    }

    private static Object readJson(Path path, Object fallback) {
        try {
            if (!Files.exists(path)) {
                return fallback;
            }
            String raw = Files.readString(path);
            if (raw.isBlank()) {
                return fallback;
            }
            return JsonUtils.readFromString(raw, Object.class);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void writeJson(Path path, Object body) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, JsonUtils.writeAsString(body));
        } catch (Exception ignored) {
            // persist best-effort
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readNotAfter(Path crt) {
        if (!Files.isRegularFile(crt)) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("openssl", "x509", "-enddate", "-noout", "-in", crt.toString())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (out.startsWith("notAfter=")) {
                return out.substring("notAfter=".length());
            }
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            try {
                return DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(Files.getLastModifiedTime(crt).toMillis()).atOffset(ZoneOffset.UTC));
            } catch (IOException ex) {
                return null;
            }
        }
    }
}
