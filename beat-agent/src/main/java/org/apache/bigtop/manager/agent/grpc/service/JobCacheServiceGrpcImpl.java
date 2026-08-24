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
package org.apache.bigtop.manager.agent.grpc.service;

import org.apache.bigtop.manager.common.constants.MessageConstants;
import org.apache.bigtop.manager.common.utils.JsonUtils;
import org.apache.bigtop.manager.common.utils.ProjectPathUtils;
import org.apache.bigtop.manager.grpc.generated.JobCacheReply;
import org.apache.bigtop.manager.grpc.generated.JobCacheRequest;
import org.apache.bigtop.manager.grpc.generated.JobCacheServiceGrpc;
import org.apache.bigtop.manager.grpc.payload.JobCachePayload;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.apache.bigtop.manager.common.constants.CacheFiles.CLUSTER_INFO;
import static org.apache.bigtop.manager.common.constants.CacheFiles.COMPONENTS_INFO;
import static org.apache.bigtop.manager.common.constants.CacheFiles.CONFIGURATIONS_INFO;
import static org.apache.bigtop.manager.common.constants.CacheFiles.HOSTS_INFO;
import static org.apache.bigtop.manager.common.constants.CacheFiles.REPOS_INFO;
import static org.apache.bigtop.manager.common.constants.CacheFiles.USERS_INFO;

@Slf4j
@GrpcService
public class JobCacheServiceGrpcImpl extends JobCacheServiceGrpc.JobCacheServiceImplBase {

    @Override
    public void save(JobCacheRequest request, StreamObserver<JobCacheReply> responseObserver) {
        try {
            JobCachePayload payload = JsonUtils.readFromString(request.getPayload(), JobCachePayload.class);
            if (Boolean.TRUE.equals(payload.getTlsDistributeOnly())) {
                writeTlsMaterial(payload);
                JobCacheReply reply = JobCacheReply.newBuilder()
                        .setCode(MessageConstants.SUCCESS_CODE)
                        .build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
                return;
            }

            String cacheDir = ProjectPathUtils.getAgentCachePath() + File.separator + payload.getClusterId();
            Path p = Paths.get(cacheDir);
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }

            String dir = p.getParent().toFile().getAbsolutePath();
            JsonUtils.writeToFile(dir + "/current", payload.getCurrentClusterId());

            JsonUtils.writeToFile(cacheDir + CONFIGURATIONS_INFO, payload.getConfigurations());
            JsonUtils.writeToFile(cacheDir + COMPONENTS_INFO, payload.getComponentHosts());
            JsonUtils.writeToFile(cacheDir + USERS_INFO, payload.getUserInfo());
            JsonUtils.writeToFile(cacheDir + REPOS_INFO, payload.getRepoInfo());
            JsonUtils.writeToFile(cacheDir + CLUSTER_INFO, payload.getClusterInfo());
            JsonUtils.writeToFile(cacheDir + HOSTS_INFO, payload.getHosts());

            JobCacheReply reply = JobCacheReply.newBuilder()
                    .setCode(MessageConstants.SUCCESS_CODE)
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    private static void writeTlsMaterial(JobCachePayload payload) throws Exception {
        String target = payload.getTlsTargetDir();
        if (target == null || target.isBlank()) {
            target = "/etc/beat/tls";
        }
        Path dir = Paths.get(target);
        Files.createDirectories(dir);
        if (payload.getTlsCaCrt() == null || payload.getTlsHostCrt() == null || payload.getTlsHostKey() == null) {
            throw new IllegalArgumentException("tlsCaCrt, tlsHostCrt and tlsHostKey are required");
        }
        Path ca = dir.resolve("ca.crt");
        Path crt = dir.resolve("host.crt");
        Path key = dir.resolve("host.key");
        Files.writeString(ca, payload.getTlsCaCrt());
        Files.writeString(crt, payload.getTlsHostCrt());
        Files.writeString(key, payload.getTlsHostKey());
        String pass = payload.getTlsStorePassword() == null ? "beatTls2026" : payload.getTlsStorePassword();
        Files.writeString(dir.resolve("store.pass"), pass);
        if (payload.getTlsKeystoreB64() != null && !payload.getTlsKeystoreB64().isBlank()) {
            Files.write(dir.resolve("keystore.p12"), java.util.Base64.getDecoder().decode(payload.getTlsKeystoreB64()));
        }
        if (payload.getTlsTruststoreB64() != null && !payload.getTlsTruststoreB64().isBlank()) {
            Files.write(
                    dir.resolve("truststore.p12"),
                    java.util.Base64.getDecoder().decode(payload.getTlsTruststoreB64()));
        }
        // Build JKS copies for Hadoop when keytool is available
        if (Boolean.TRUE.equals(payload.getTlsWriteJks())
                && Files.isRegularFile(dir.resolve("keystore.p12"))
                && Files.isRegularFile(dir.resolve("truststore.p12"))) {
            try {
                exec(
                        "keytool",
                        "-importkeystore",
                        "-noprompt",
                        "-srckeystore",
                        dir.resolve("keystore.p12").toString(),
                        "-srcstoretype",
                        "PKCS12",
                        "-srcstorepass",
                        pass,
                        "-destkeystore",
                        dir.resolve("keystore.jks").toString(),
                        "-deststoretype",
                        "JKS",
                        "-deststorepass",
                        pass);
                exec(
                        "keytool",
                        "-importkeystore",
                        "-noprompt",
                        "-srckeystore",
                        dir.resolve("truststore.p12").toString(),
                        "-srcstoretype",
                        "PKCS12",
                        "-srcstorepass",
                        pass,
                        "-destkeystore",
                        dir.resolve("truststore.jks").toString(),
                        "-deststoretype",
                        "JKS",
                        "-deststorepass",
                        pass);
            } catch (Exception e) {
                log.warn("JKS conversion skipped: {}", e.getMessage());
            }
        }
        try {
            Files.setPosixFilePermissions(ca, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
            Files.setPosixFilePermissions(crt, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
            Files.setPosixFilePermissions(
                    key,
                    java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // non-posix FS
        }
        String host = payload.getTlsHostname() == null ? "" : payload.getTlsHostname();
        Files.writeString(dir.resolve("hostname"), host + "\n");
        Files.writeString(dir.resolve("ENABLED"), "true\n");
        log.info("AutoTLS material written to {} for {}", target, host);
    }

    private static void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", cmd));
        }
    }
}
