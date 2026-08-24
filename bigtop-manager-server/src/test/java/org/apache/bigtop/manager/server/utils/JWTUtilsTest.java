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

import org.junit.jupiter.api.Test;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JWTUtilsTest {

    private static JWTUtils newJwtUtilsWithDevSecretAllowed() {
        JwtProperties props = new JwtProperties();
        props.setAllowDefaultSecret(true);
        props.setIssuer("bigtop-manager");
        props.setAudience("bigtop-manager");
        props.setExpirationDays(7);
        return new JWTUtils(props);
    }

    @Test
    public void testGenerateTokenNormal() {
        JWTUtils jwtUtils = newJwtUtilsWithDevSecretAllowed();

        Long id = 1L;
        String username = "testUser";
        Integer tokenVersion = 1;
        String token = jwtUtils.generateToken(id, username, tokenVersion);
        assertNotNull(token);

        DecodedJWT decodedJWT = jwtUtils.resolveToken(token);
        assertEquals(id, decodedJWT.getClaim(JWTUtils.CLAIM_ID).asLong());
        assertEquals(username, decodedJWT.getClaim(JWTUtils.CLAIM_USERNAME).asString());
        assertEquals(
                tokenVersion, decodedJWT.getClaim(JWTUtils.CLAIM_TOKEN_VERSION).asInt());
    }

    @Test
    public void testResolveTokenExpired() {
        JWTUtils jwtUtils = newJwtUtilsWithDevSecretAllowed();

        Long id = 2L;
        String username = "expiredUser";
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR_OF_DAY, -1);
        Date date = calendar.getTime();

        String token = JWT.create()
                .withIssuer("bigtop-manager")
                .withAudience("bigtop-manager")
                .withIssuedAt(new Date())
                .withClaim(JWTUtils.CLAIM_ID, id)
                .withClaim(JWTUtils.CLAIM_USERNAME, username)
                .withExpiresAt(date)
                .sign(Algorithm.HMAC256(JWTUtils.DEFAULT_DEV_SECRET));

        assertThrows(JWTVerificationException.class, () -> jwtUtils.resolveToken(token));
    }

    @Test
    public void testResolveTokenIllegal() {
        JWTUtils jwtUtils = newJwtUtilsWithDevSecretAllowed();

        String illegalToken =
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        assertThrows(JWTVerificationException.class, () -> jwtUtils.resolveToken(illegalToken));
    }

    @Test
    public void testResolveTokenWrongFormat() {
        JWTUtils jwtUtils = newJwtUtilsWithDevSecretAllowed();

        String wrongFormatToken = "wrong_format_token";
        assertThrows(JWTDecodeException.class, () -> jwtUtils.resolveToken(wrongFormatToken));
    }

    @Test
    public void testGenerateTokenUsernameEmpty() {
        JWTUtils jwtUtils = newJwtUtilsWithDevSecretAllowed();

        String token = jwtUtils.generateToken(1L, "", 1);
        assertNotNull(token);

        DecodedJWT decodedJWT = jwtUtils.resolveToken(token);
        assertEquals("", decodedJWT.getClaim(JWTUtils.CLAIM_USERNAME).asString());
    }

    @Test
    public void testResolveTokenMissingIatRejected() {
        JWTUtils jwtUtils = newJwtUtilsWithDevSecretAllowed();

        String token = JWT.create()
                .withIssuer("bigtop-manager")
                .withAudience("bigtop-manager")
                .withClaim(JWTUtils.CLAIM_ID, 1L)
                .withClaim(JWTUtils.CLAIM_TOKEN_VERSION, 1)
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                // intentionally no iat
                .sign(Algorithm.HMAC256(JWTUtils.DEFAULT_DEV_SECRET));

        assertThrows(JWTVerificationException.class, () -> jwtUtils.resolveToken(token));
    }

    @Test
    public void testSecretRequiredByDefault() {
        JwtProperties props = new JwtProperties();
        props.setAllowDefaultSecret(false);
        JWTUtils jwtUtils = new JWTUtils(props);

        assertThrows(IllegalStateException.class, () -> jwtUtils.generateToken(1L, "u", 1));
    }
}
