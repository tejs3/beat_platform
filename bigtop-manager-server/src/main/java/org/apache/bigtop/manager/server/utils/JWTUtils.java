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
package org.apache.bigtop.manager.server.utils;

import org.apache.bigtop.manager.server.config.JwtProperties;

import org.springframework.stereotype.Component;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
public class JWTUtils {

    public static final String CLAIM_ID = "id";

    public static final String CLAIM_USERNAME = "username";

    public static final String CLAIM_TOKEN_VERSION = "token_version";

    /**
     * Dev-only fallback secret to preserve local boot for contributors.
     * <p>
     * In production, configure `bigtop-manager.security.jwt.secret`.
     */
    static final String DEFAULT_DEV_SECRET = "r0PGVyvjKOxUBwGt";

    private final JwtProperties jwtProperties;

    public JWTUtils(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String generateToken(Long userId, String username, Integer tokenVersion) {
        Instant now = Instant.now();
        Instant expireTime = now.plus(jwtProperties.getExpirationDays(), ChronoUnit.DAYS);

        return JWT.create()
                .withIssuer(jwtProperties.getIssuer())
                .withAudience(jwtProperties.getAudience())
                .withIssuedAt(Date.from(now))
                .withClaim(CLAIM_ID, userId)
                .withClaim(CLAIM_USERNAME, username)
                .withClaim(CLAIM_TOKEN_VERSION, tokenVersion)
                .withExpiresAt(Date.from(expireTime))
                .sign(Algorithm.HMAC256(getSigningSecret()));
    }

    public DecodedJWT resolveToken(String token) throws JWTVerificationException {
        Algorithm algorithm = Algorithm.HMAC256(getSigningSecret());
        JWTVerifier verifier = JWT.require(algorithm)
                .withIssuer(jwtProperties.getIssuer())
                .withAudience(jwtProperties.getAudience())
                .build();

        DecodedJWT decodedJWT = verifier.verify(token);

        // Enforce issued-at to mitigate tokens without freshness metadata.
        Date issuedAt = decodedJWT.getIssuedAt();
        if (issuedAt == null) {
            throw new JWTVerificationException("Missing iat");
        }

        // Reject tokens issued too far in the future (clock skew).
        Instant now = Instant.now();
        if (issuedAt.toInstant().isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new JWTVerificationException("iat is in the future");
        }

        return decodedJWT;
    }

    private String getSigningSecret() {
        String secret = jwtProperties.getSecret();
        if (secret != null && !secret.isBlank()) {
            return secret;
        }

        if (jwtProperties.isAllowDefaultSecret()) {
            return DEFAULT_DEV_SECRET;
        }

        throw new IllegalStateException(
                "JWT secret is not configured. Please set bigtop-manager.security.jwt.secret (or enable allowDefaultSecret for dev only).");
    }
}
