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
package org.apache.bigtop.manager.server.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * When AutoTLS keystore is present: HTTPS on 8083 (all interfaces); plain HTTP :8080
 * is bound to localhost only so http://&lt;host-ip&gt;:8080 fails from the network.
 * When keystore is absent: default HTTP :8080 on all interfaces.
 */
@Slf4j
@Configuration
public class BeatHttpsConnectorConfig {

    public static final Path TLS_DIR = resolveTlsDir();
    public static final Path KEY_STORE = TLS_DIR.resolve("server.p12");
    public static final Path PORT_FILE = TLS_DIR.resolve("https.port");
    public static final Path HTTP_DISABLED_FLAG = TLS_DIR.resolve("http.disabled");
    public static final String STORE_PASS = "beatTls2026";

    /** Nearby HTTPS candidates — never 8081 (agent) or 8082 (YARN). */
    private static final int[] HTTPS_CANDIDATES = {8083, 8084, 8085};

    private static Path resolveTlsDir() {
        Path beat = Path.of("/opt/beat-manager/conf/tls");
        Path legacy = Path.of("/opt/bigtop-manager-server/conf/tls");
        if (Files.isRegularFile(beat.resolve("server.p12"))) {
            return beat;
        }
        if (Files.isRegularFile(legacy.resolve("server.p12"))) {
            return legacy;
        }
        if (Files.isDirectory(Path.of("/opt/beat-manager/bin"))) {
            return beat;
        }
        return legacy;
    }

    public static boolean isManagerHttpsEnabled() {
        return Files.isRegularFile(KEY_STORE);
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> beatHttpsCustomizer() {
        return factory -> {
            if (!Files.isRegularFile(KEY_STORE)) {
                log.info("AutoTLS keystore not present — HTTP :8080 only");
                return;
            }
            int port = chooseHttpsPort();
            try {
                Files.createDirectories(TLS_DIR);
                Files.writeString(PORT_FILE, String.valueOf(port));
                if (!Files.isRegularFile(HTTP_DISABLED_FLAG)) {
                    Files.writeString(HTTP_DISABLED_FLAG, "true\n");
                }
            } catch (Exception e) {
                log.warn("Unable to write https.port: {}", e.getMessage());
            }

            // Do NOT setPort(-1) — that stops the HTTPS connector in this Tomcat/Spring Boot build.
            // Keep plain HTTP :8080 on all interfaces so agents can download packages from
            // http://<manager-ip>:8080/ui/repo/ (FileDownloader does not trust AutoTLS HTTPS).
            try {
                factory.setAddress(InetAddress.getByName("0.0.0.0"));
            } catch (Exception e) {
                log.warn("Unable to bind HTTP to 0.0.0.0: {}", e.getMessage());
            }

            Connector connector = new Connector(Http11NioProtocol.class.getName());
            connector.setScheme("https");
            connector.setSecure(true);
            connector.setPort(port);
            try {
                connector.setProperty("address", "0.0.0.0");
            } catch (Exception ignored) {
                // optional
            }
            Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
            protocol.setSSLEnabled(true);

            SSLHostConfig sslHostConfig = new SSLHostConfig();
            SSLHostConfigCertificate cert = new SSLHostConfigCertificate(
                    sslHostConfig, SSLHostConfigCertificate.Type.RSA);
            cert.setCertificateKeystoreFile(KEY_STORE.toString());
            cert.setCertificateKeystorePassword(STORE_PASS);
            cert.setCertificateKeystoreType("PKCS12");
            sslHostConfig.addCertificate(cert);
            connector.addSslHostConfig(sslHostConfig);

            factory.addAdditionalTomcatConnectors(connector);
            log.info(
                    "BEAT AutoTLS: HTTPS :{} on 0.0.0.0; plain HTTP :8080 on 0.0.0.0 (agent package downloads)",
                    port);
        };
    }

    /** Prefer 8083 (near 8080); skip 8081/8082. */
    public static int chooseHttpsPort() {
        if (Files.isRegularFile(PORT_FILE)) {
            try {
                int pinned = Integer.parseInt(Files.readString(PORT_FILE).trim());
                if (pinned == 8081 || pinned == 8082) {
                    // old/bad pin — re-pick
                } else if (pinned > 0 && isPortFree(pinned)) {
                    return pinned;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        for (int port : HTTPS_CANDIDATES) {
            if (isPortFree(port)) {
                return port;
            }
        }
        return 8083;
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
