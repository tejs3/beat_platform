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
 */
package org.apache.bigtop.manager.server.service.alephys;

import org.apache.bigtop.manager.common.constants.Caches;
import org.apache.bigtop.manager.common.constants.MessageConstants;
import org.apache.bigtop.manager.common.utils.JsonUtils;
import org.apache.bigtop.manager.dao.po.HostPO;
import org.apache.bigtop.manager.dao.po.UserPO;
import org.apache.bigtop.manager.dao.repository.HostDao;
import org.apache.bigtop.manager.dao.repository.UserDao;
import org.apache.bigtop.manager.grpc.generated.JobCacheReply;
import org.apache.bigtop.manager.grpc.generated.JobCacheRequest;
import org.apache.bigtop.manager.grpc.generated.JobCacheServiceGrpc;
import org.apache.bigtop.manager.grpc.payload.JobCachePayload;
import org.apache.bigtop.manager.server.enums.ApiExceptionEnum;
import org.apache.bigtop.manager.server.exception.ApiException;
import org.apache.bigtop.manager.server.grpc.GrpcClient;
import org.apache.bigtop.manager.server.model.converter.UserConverter;
import org.apache.bigtop.manager.server.model.vo.LoginVO;
import org.apache.bigtop.manager.server.model.vo.UserVO;
import org.apache.bigtop.manager.server.utils.CacheUtils;
import org.apache.bigtop.manager.server.utils.JWTUtils;
import org.apache.bigtop.manager.server.utils.PasswordUtils;

import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class AlephysIdentityService {

    public static final Path DIR_USERS = AlephysStore.ROOT.resolve("directory-users.json");
    public static final Path KDC_STATUS = AlephysStore.ROOT.resolve("kdc.json");

    @Resource
    private AlephysStore store;

    @Resource
    private UserDao userDao;

    @Resource
    private HostDao hostDao;

    @Resource
    private org.apache.bigtop.manager.server.service.alephys.AlephysAutoTlsService autoTlsService;

    @Resource
    private JWTUtils jwtUtils;

    public Map<String, Object> loginOptions() {
        store.ensureDefaults();
        Map<String, Object> ident = asMap(store.readIdentity());
        Map<String, Object> m = new LinkedHashMap<>();
        boolean dirOn = bool(ident.get("directoryEnabled")) || Files.isRegularFile(DIR_USERS);
        m.put("directoryEnabled", dirOn);
        m.put("localHint", "admin / admin (break-glass local account)");
        String ldapUrl = String.valueOf(ident.getOrDefault("ldapUrl", "")).trim();
        boolean ldapOn = dirOn && !ldapUrl.isEmpty();
        m.put(
                "directoryHint",
                ldapOn
                        ? "Use your LDAP username and password"
                        : dirOn
                                ? "Lab file users: operator, analyst, gao, renault — or save LDAP under System → Identity / TLS"
                                : "Save LDAP under System → Identity / TLS, then use this tab");
        m.put("ldapUrl", ldapUrl);
        return m;
    }

    public Map<String, Object> identityForUi() {
        store.ensureDefaults();
        Map<String, Object> ident = asMap(store.readIdentity());
        Object pw = ident.get("bindPassword");
        ident.put("bindPasswordSet", pw != null && !String.valueOf(pw).isBlank());
        ident.put("bindPassword", "");
        return ident;
    }

    public Map<String, Object> saveLdap(Map<String, Object> body) {
        store.ensureDefaults();
        Map<String, Object> ident = asMap(store.readIdentity());
        if (body == null) {
            throw new ApiException(ApiExceptionEnum.OPERATION_FAILED, "request body is required");
        }
        putStr(ident, body, "ldapUrl");
        putStr(ident, body, "userDnTemplate");
        putStr(ident, body, "baseDn");
        putStr(ident, body, "bindDn");
        putStr(ident, body, "searchFilter");
        Object incomingPw = body.get("bindPassword");
        if (incomingPw != null && !String.valueOf(incomingPw).isBlank()) {
            ident.put("bindPassword", String.valueOf(incomingPw));
        }
        String url = String.valueOf(ident.getOrDefault("ldapUrl", "")).trim();
        if (url.isEmpty()) {
            throw new ApiException(ApiExceptionEnum.OPERATION_FAILED, "LDAP URL is required");
        }
        ident.put("directoryEnabled", true);
        ident.put("loginMode", "local+directory");
        ident.put(
                "loginNote",
                "Directory tab binds to the LDAP URL saved here. Local admin stays break-glass.");
        store.writeIdentity(ident);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("identity", identityForUi());
        return out;
    }

    public Map<String, Object> testLdap(Map<String, Object> body) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (body != null) {
            cfg.putAll(body);
        }
        Map<String, Object> saved = asMap(store.readIdentity());
        if (blank(cfg.get("bindPassword")) && !blank(saved.get("bindPassword"))) {
            cfg.put("bindPassword", saved.get("bindPassword"));
        }
        String user = body == null ? "" : String.valueOf(body.getOrDefault("username", "")).trim();
        String pass = body == null ? "" : String.valueOf(body.getOrDefault("password", ""));
        Map<String, Object> out = new LinkedHashMap<>();
        if (user.isEmpty() || pass.isEmpty() || blank(cfg.get("ldapUrl"))) {
            out.put("ok", false);
            out.put("message", "ldapUrl, username and password are required");
            return out;
        }
        try {
            String dn = resolveUserDn(cfg, user);
            bindAs(cfg, dn, pass);
            out.put("ok", true);
            out.put("dn", dn);
            out.put("message", "bind ok");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return out;
    }

    public Map<String, Object> enableDirectory() {
        store.ensureDefaults();
        try {
            Files.createDirectories(AlephysStore.ROOT);
            if (!Files.isRegularFile(DIR_USERS)) {
                List<Map<String, Object>> users = new ArrayList<>();
                users.add(dirUser("operator", "Operator@123", "Lab Operator", "operator"));
                users.add(dirUser("analyst", "Analyst@123", "Lab Analyst", "viewer"));
                users.add(dirUser("gao", "GaoUser@123", "GAO analyst", "viewer"));
                users.add(dirUser("renault", "RenaultUser@123", "Renault analyst", "viewer"));
                Files.writeString(DIR_USERS, JsonUtils.writeAsString(users));
            }
        } catch (Exception e) {
            throw new ApiException(ApiExceptionEnum.OPERATION_FAILED, "directory enable failed");
        }
        Map<String, Object> ident = asMap(store.readIdentity());
        ident.put("loginMode", "local+directory");
        ident.put("directoryEnabled", true);
        ident.put("ldapUrl", ident.getOrDefault("ldapUrl", "ldap://127.0.0.1:389"));
        ident.put(
                "loginNote",
                "Local admin stays break-glass. Directory tab uses LDAP or lab file users.");
        store.writeIdentity(ident);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identity", store.readIdentity());
        out.put("users", List.of("operator", "analyst", "gao", "renault"));
        out.put(
                "passwordsNote",
                "Default lab passwords: Operator@123, Analyst@123, GaoUser@123, RenaultUser@123 — change after first login.");
        return out;
    }

    @SuppressWarnings("unchecked")
    public LoginVO loginDirectory(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new ApiException(ApiExceptionEnum.USERNAME_OR_PASSWORD_REQUIRED);
        }
        if ("admin".equalsIgnoreCase(username.trim())) {
            throw new ApiException(ApiExceptionEnum.INCORRECT_USERNAME_OR_PASSWORD);
        }
        store.ensureDefaults();
        Map<String, Object> ident = asMap(store.readIdentity());
        boolean ok = false;
        try {
            ok = ldapBind(ident, username.trim(), password);
        } catch (Exception ignored) {
            ok = false;
        }
        if (!ok) {
            ok = fileBind(username.trim(), password);
        }
        if (!ok) {
            throw new ApiException(ApiExceptionEnum.INCORRECT_USERNAME_OR_PASSWORD);
        }
        UserPO user = ensureBmUser(username.trim(), nicknameOf(username.trim()));
        UserVO userVO = UserConverter.INSTANCE.fromPO2VO(user);
        CacheUtils.setCache(
                Caches.CACHE_USER, user.getId().toString(), userVO, Caches.USER_EXPIRE_TIME_DAYS, TimeUnit.DAYS);
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtils.generateToken(user.getId(), user.getUsername(), user.getTokenVersion()));
        return vo;
    }

    public Map<String, Object> enableTlsHosts() {
        store.initTls();
        Path hostsDir = AlephysStore.TLS_DIR.resolve("hosts");
        try {
            Files.createDirectories(hostsDir);
            Path caCrt = AlephysStore.TLS_DIR.resolve("ca.crt");
            Path caKey = AlephysStore.TLS_DIR.resolve("ca.key");
            for (String cn : clusterHostnames()) {
                Path key = hostsDir.resolve(cn + ".key");
                Path csr = hostsDir.resolve(cn + ".csr");
                Path crt = hostsDir.resolve(cn + ".crt");
                if (Files.isRegularFile(crt) && Files.isRegularFile(key)) {
                    continue;
                }
                exec("openssl",
                        "req",
                        "-new",
                        "-newkey",
                        "rsa:2048",
                        "-nodes",
                        "-subj",
                        "/CN=" + cn + "/O=BEAT",
                        "-keyout",
                        key.toString(),
                        "-out",
                        csr.toString());
                exec("openssl",
                        "x509",
                        "-req",
                        "-in",
                        csr.toString(),
                        "-CA",
                        caCrt.toString(),
                        "-CAkey",
                        caKey.toString(),
                        "-CAcreateserial",
                        "-days",
                        "365",
                        "-out",
                        crt.toString());
            }
        } catch (Exception ignored) {
            // status still reports what exists
        }
        return store.tlsStatus();
    }

    public Map<String, Object> distributeTls() {
        return distributeTls(null);
    }

    public Map<String, Object> distributeTls(Map<String, Object> body) {
        return autoTlsService.enable(body);
    }

    public Map<String, Object> disableTls() {
        return autoTlsService.disable();
    }

    private List<String> clusterHostnames() {
        Set<String> names = new LinkedHashSet<>();
        List<HostPO> hosts = hostDao.findAll();
        if (hosts != null) {
            for (HostPO h : hosts) {
                if (h.getHostname() != null && !h.getHostname().isBlank()) {
                    names.add(h.getHostname().trim());
                }
            }
        }
        if (names.isEmpty()) {
            names.add("ravitejacdp1.infra.alephys.com");
            names.add("ravitejacdp2.infra.alephys.com");
            names.add("ravitejacdp3.infra.alephys.com");
        }
        return new ArrayList<>(names);
    }

    public Map<String, Object> enableKdc() {
        store.ensureDefaults();
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("realm", "ALEPHYS.LAB");
        st.put("kdcHost", "ravitejacdp1.infra.alephys.com");
        try {
            Files.createDirectories(AlephysStore.ROOT);
            int code = exec(
                    "bash",
                    "-lc",
                    "rpm -q krb5-server >/dev/null 2>&1 || yum install -y krb5-server krb5-workstation >/dev/null 2>&1; "
                            + "if [ ! -f /var/kerberos/krb5kdc/principal ]; then "
                            + "cat >/etc/krb5.conf <<'EOF'\n"
                            + "[libdefaults]\n default_realm = ALEPHYS.LAB\n dns_lookup_realm = false\n dns_lookup_kdc = false\n"
                            + "[realms]\n ALEPHYS.LAB = {\n  kdc = ravitejacdp1.infra.alephys.com\n  admin_server = ravitejacdp1.infra.alephys.com\n }\n"
                            + "[domain_realm]\n .infra.alephys.com = ALEPHYS.LAB\n infra.alephys.com = ALEPHYS.LAB\nEOF\n"
                            + "kdb5_util create -s -P LabKdc@123 >/dev/null 2>&1 || true; "
                            + "systemctl enable --now krb5kdc kadmin >/dev/null 2>&1 || true; "
                            + "fi; "
                            + "systemctl is-active krb5kdc || true");
            st.put("kdcActive", code == 0 || isKdcActive());
        } catch (Exception e) {
            st.put("kdcActive", isKdcActive());
            st.put("error", e.getMessage());
        }
        st.put(
                "hadoopNote",
                "Lab KDC is separate from any production directory. Hadoop/Hive/Spark stay SIMPLE until a dedicated cutover restart.");
        try {
            Files.writeString(KDC_STATUS, JsonUtils.writeAsString(st));
        } catch (Exception ignored) {
            // ignore
        }
        Map<String, Object> ident = asMap(store.readIdentity());
        ident.put("kerberosRealm", "ALEPHYS.LAB");
        ident.put("kdcReady", Boolean.TRUE.equals(st.get("kdcActive")));
        store.writeIdentity(ident);
        return st;
    }

    public Map<String, Object> kdcStatus() {
        if (Files.isRegularFile(KDC_STATUS)) {
            try {
                Object raw = JsonUtils.readFromString(Files.readString(KDC_STATUS), Object.class);
                if (raw instanceof Map<?, ?> m) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    m.forEach((k, v) -> out.put(String.valueOf(k), v));
                    out.put("kdcActive", isKdcActive());
                    return out;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kdcActive", isKdcActive());
        m.put("realm", "ALEPHYS.LAB");
        return m;
    }

    private static Map<String, Object> dirUser(String user, String pass, String nick, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", user);
        m.put("passwordHash", PasswordUtils.getBcryptPassword(pass));
        m.put("nickname", nick);
        m.put("role", role);
        return m;
    }

    @SuppressWarnings("unchecked")
    private boolean fileBind(String username, String password) {
        if (!Files.isRegularFile(DIR_USERS)) {
            return false;
        }
        try {
            Object raw = JsonUtils.readFromString(Files.readString(DIR_USERS), Object.class);
            if (!(raw instanceof List<?> list)) {
                return false;
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                if (!username.equals(String.valueOf(m.get("username")))) {
                    continue;
                }
                Object hash = m.get("passwordHash");
                return hash != null && PasswordUtils.checkBcryptPassword(password, String.valueOf(hash));
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private boolean ldapBind(Map<String, Object> ident, String username, String password) {
        if (blank(ident.get("ldapUrl"))) {
            return false;
        }
        try {
            String dn = resolveUserDn(ident, username);
            bindAs(ident, dn, password);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveUserDn(Map<String, Object> ident, String username) throws Exception {
        String template =
                str(ident.getOrDefault("userDnTemplate", "uid={0},ou=people,dc=alephys,dc=lab"));
        String baseDn = str(ident.get("baseDn"));
        String bindDn = str(ident.get("bindDn"));
        if (!baseDn.isEmpty() && !bindDn.isEmpty()) {
            String filter = str(ident.getOrDefault("searchFilter", "(uid={0})")).replace("{0}", ldapEscape(username));
            Hashtable<String, String> env = ldapEnv(ident, bindDn, str(ident.get("bindPassword")));
            DirContext ctx = new InitialDirContext(env);
            try {
                SearchControls sc = new SearchControls();
                sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
                sc.setCountLimit(1);
                NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, sc);
                if (results.hasMore()) {
                    return results.next().getNameInNamespace();
                }
            } finally {
                ctx.close();
            }
            throw new IllegalStateException("user not found in LDAP");
        }
        return template.replace("{0}", username);
    }

    private static void bindAs(Map<String, Object> ident, String dn, String password) throws Exception {
        DirContext ctx = new InitialDirContext(ldapEnv(ident, dn, password));
        ctx.close();
    }

    private static Hashtable<String, String> ldapEnv(Map<String, Object> ident, String principal, String password) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, str(ident.get("ldapUrl")));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password == null ? "" : password);
        env.put("com.sun.jndi.ldap.connect.timeout", "4000");
        env.put("com.sun.jndi.ldap.read.timeout", "6000");
        return env;
    }

    private static void putStr(Map<String, Object> ident, Map<String, Object> body, String key) {
        if (body.containsKey(key) && body.get(key) != null) {
            ident.put(key, String.valueOf(body.get(key)).trim());
        }
    }

    private static boolean blank(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String ldapEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private UserPO ensureBmUser(String username, String nickname) {
        UserPO existing = userDao.findByUsername(username);
        if (existing != null) {
            return existing;
        }
        UserPO u = new UserPO();
        u.setUsername(username);
        u.setNickname(nickname);
        u.setStatus(true);
        u.setTokenVersion(1);
        u.setPassword(PasswordUtils.getBcryptPassword(PasswordUtils.randomString(24)));
        userDao.save(u);
        UserPO created = userDao.findByUsername(username);
        return created != null ? created : u;
    }

    private String nicknameOf(String username) {
        if (!Files.isRegularFile(DIR_USERS)) {
            return username;
        }
        try {
            Object raw = JsonUtils.readFromString(Files.readString(DIR_USERS), Object.class);
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && username.equals(String.valueOf(m.get("username")))) {
                        Object n = m.get("nickname");
                        return n == null ? username : String.valueOf(n);
                    }
                }
            }
        } catch (Exception ignored) {
            // username fallback
        }
        return username;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static boolean isKdcActive() {
        try {
            Process p = new ProcessBuilder("bash", "-lc", "systemctl is-active krb5kdc").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return "active".equals(out);
        } catch (Exception e) {
            return false;
        }
    }

    private static int exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(120, TimeUnit.SECONDS);
        return p.exitValue();
    }
}
