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
package org.apache.bigtop.manager.stack.core.utils;

import org.apache.bigtop.manager.common.constants.Constants;
import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.grpc.payload.ComponentCommandPayload;
import org.apache.bigtop.manager.stack.core.enums.ConfigType;
import org.apache.bigtop.manager.stack.core.spi.param.BaseParams;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxFileUtils;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxOSUtils;

import org.apache.commons.lang3.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Cloudera Manager–style process directories for BEAT Manager.
 *
 * <p>Each configure/start creates:
 * {@code /var/run/beat-agent/process/<epoch>-<service>-<component>/}
 * with rendered configs, TLS material, and metadata. Older process dirs are kept
 * for rollback/inspection. A {@code current-<service>-<component>} symlink points
 * at the latest process dir.
 */
@Slf4j
public final class BeatProcessDirs {

    public static final String ROOT = "/var/run/beat-agent/process";

    public static final String TLS_SOURCE_DIR = "/etc/beat/tls";

    private BeatProcessDirs() {}

    public static boolean enabled(Params params) {
        return StringUtils.isNotBlank(processId(params));
    }

    public static String processId(Params params) {
        if (!(params instanceof BaseParams baseParams)) {
            return null;
        }
        ComponentCommandPayload payload = baseParams.getPayload();
        return payload == null ? null : payload.getProcessId();
    }

    public static String dirFor(String processId) {
        return ROOT + "/" + processId;
    }

    public static String currentLinkName(String serviceName, String componentName) {
        return ROOT + "/current-" + serviceName + "-" + componentName;
    }

    /** Create process root + this process dir before configs are written. */
    public static void prepare(Params params) {
        if (!enabled(params)) {
            return;
        }
        String processDir = dirFor(processId(params));
        log.info("Preparing BEAT process dir: {}", processDir);
        LinuxFileUtils.createDirectories(ROOT, "root", "root", Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(processDir, params.user(), params.group(), Constants.PERMISSION_755, true);
    }

    /**
     * After configure/start: bundle TLS, write metadata, publish current symlink,
     * and mirror configs into the package legacy conf dir so daemons that ignore
     * process dirs still pick up the latest files.
     */
    public static void finalizeProcess(Params params) {
        if (!enabled(params) || !(params instanceof BaseParams baseParams)) {
            return;
        }
        String id = processId(params);
        String processDir = dirFor(id);
        if (!Files.isDirectory(Path.of(processDir))) {
            log.warn("Process dir missing, skipping finalize: {}", processDir);
            return;
        }

        bundleTls(processDir, params.user(), params.group());
        writeProcessMetadata(baseParams, processDir);
        publishCurrent(baseParams.getServiceName(), baseParams.getPayload().getComponentName(), processDir);
        mirrorToLegacyConf(baseParams);
        log.info("Finalized BEAT process dir: {}", processDir);
    }

    private static void bundleTls(String processDir, String user, String group) {
        Path tls = Path.of(TLS_SOURCE_DIR);
        if (!Files.isDirectory(tls)) {
            return;
        }
        copyIfExists(TLS_SOURCE_DIR + "/ca.crt", processDir + "/beat-auto-in_cluster_ca_cert.pem", user, group, "644");
        copyIfExists(TLS_SOURCE_DIR + "/host.crt", processDir + "/beat-auto-host_cert_chain.pem", user, group, "644");
        copyIfExists(TLS_SOURCE_DIR + "/host.key", processDir + "/beat-auto-host_key.pem", user, group, "400");
        copyIfExists(TLS_SOURCE_DIR + "/ca.crt", processDir + "/beat-auto-global_cacerts.pem", user, group, "644");
        copyIfExists(TLS_SOURCE_DIR + "/host.p12", processDir + "/beat-auto-host_keystore.p12", user, group, "400");
        copyIfExists(
                TLS_SOURCE_DIR + "/truststore.jks", processDir + "/beat-auto-in_cluster_truststore.jks", user, group, "644");
        copyIfExists(
                TLS_SOURCE_DIR + "/truststore.jks", processDir + "/beat-auto-global_truststore.jks", user, group, "644");
    }

    private static void copyIfExists(String src, String dest, String user, String group, String perms) {
        if (!Files.exists(Path.of(src))) {
            return;
        }
        try {
            ShellResult r = LinuxOSUtils.sudoExecCmd("cp -a '" + src + "' '" + dest + "'");
            if (r.getExitCode() != 0) {
                log.warn("Failed to copy {} -> {}: {}", src, dest, r.getErrMsg());
                return;
            }
            LinuxFileUtils.updateOwner(dest, user, group, false);
            LinuxFileUtils.updatePermissions(dest, perms, false);
        } catch (Exception e) {
            log.warn("Failed to bundle {} into process dir: {}", src, e.getMessage());
        }
    }

    private static void writeProcessMetadata(BaseParams params, String processDir) {
        ComponentCommandPayload payload = params.getPayload();
        String content = String.format(
                """
                {
                  "processId": "%s",
                  "serviceName": "%s",
                  "componentName": "%s",
                  "command": "%s",
                  "createdAt": "%s",
                  "hostname": "%s",
                  "confDir": "%s",
                  "legacyConfDir": "%s"
                }
                """,
                payload.getProcessId(),
                payload.getServiceName(),
                payload.getComponentName(),
                payload.getCommand(),
                Instant.now().toString(),
                params.hostname(),
                params.confDir(),
                params.legacyConfDir());
        try {
            LinuxFileUtils.toFile(
                    ConfigType.CONTENT, processDir + "/process.json", "root", "root", "600", content);
        } catch (Exception e) {
            log.warn("Failed to write process.json: {}", e.getMessage());
        }
    }

    private static void publishCurrent(String serviceName, String componentName, String processDir) {
        String link = currentLinkName(serviceName, componentName);
        try {
            ShellResult r = LinuxOSUtils.sudoExecCmd("ln -sfn '" + processDir + "' '" + link + "'");
            if (r.getExitCode() != 0) {
                log.warn("Failed to publish current symlink {}: {}", link, r.getErrMsg());
            } else {
                log.info("Published current process: {} -> {}", link, processDir);
            }
        } catch (Exception e) {
            log.warn("Failed to publish current symlink: {}", e.getMessage());
        }
    }

    private static void mirrorToLegacyConf(BaseParams params) {
        String processDir = params.confDir();
        String legacy = params.legacyConfDir();
        if (StringUtils.isBlank(legacy) || processDir.equals(legacy)) {
            return;
        }
        try {
            LinuxFileUtils.createDirectories(legacy, params.user(), params.group(), Constants.PERMISSION_755, true);
            ShellResult r = LinuxOSUtils.sudoExecCmd(
                    "bash -lc \"cp -a '" + processDir + "/.' '" + legacy + "/' 2>/dev/null || true\"");
            if (r.getExitCode() != 0) {
                log.warn("Mirror to legacy conf failed: {}", r.getErrMsg());
            } else {
                log.info("Mirrored process configs to legacy conf dir: {}", legacy);
            }
        } catch (Exception e) {
            log.warn("Mirror to legacy conf failed: {}", e.getMessage());
        }
    }
}
