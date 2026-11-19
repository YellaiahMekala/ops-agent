// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Mints signed test tokens. THROWAWAY KEYS: both key pairs are generated in memory per JVM and never leave the test
 * run; only the public half of the trusted pair is written to a temp file, which the application reads as its trust
 * anchor (the production setting is the realm's JWKS URI). Nothing here is a secret and nothing is committed.
 */
final class TestJwt {

    static final String ISSUER = "https://issuer.test.invalid/realms/platform";
    static final String AUDIENCE = "ops-agent";

    private static final KeyPair TRUSTED = generate();
    private static final KeyPair UNTRUSTED = generate();
    private static final String PUBLIC_KEY_LOCATION = writePublicKey(TRUSTED);

    private TestJwt() {
    }

    static String publicKeyLocation() {
        return PUBLIC_KEY_LOCATION;
    }

    static String sender(String tenant) {
        return token(TRUSTED, ISSUER, AUDIENCE, tenant, List.of("OPS_ALERT_SENDER"), Instant.now().plusSeconds(300));
    }

    static String viewer(String tenant) {
        return token(TRUSTED, ISSUER, AUDIENCE, tenant, List.of("OPS_RUN_VIEWER"), Instant.now().plusSeconds(300));
    }

    static String withRoles(String tenant, List<String> roles) {
        return token(TRUSTED, ISSUER, AUDIENCE, tenant, roles, Instant.now().plusSeconds(300));
    }

    static String expired(String tenant) {
        return token(TRUSTED, ISSUER, AUDIENCE, tenant, List.of("OPS_RUN_VIEWER", "OPS_ALERT_SENDER"),
                Instant.now().minusSeconds(3600));
    }

    static String wrongAudience(String tenant) {
        return token(TRUSTED, ISSUER, "ai-gateway", tenant, List.of("OPS_RUN_VIEWER", "OPS_ALERT_SENDER"),
                Instant.now().plusSeconds(300));
    }

    static String wrongIssuer(String tenant) {
        return token(TRUSTED, "https://other.test.invalid/realms/x", AUDIENCE, tenant,
                List.of("OPS_RUN_VIEWER", "OPS_ALERT_SENDER"), Instant.now().plusSeconds(300));
    }

    static String forged(String tenant) {
        return token(UNTRUSTED, ISSUER, AUDIENCE, tenant, List.of("OPS_RUN_VIEWER", "OPS_ALERT_SENDER"),
                Instant.now().plusSeconds(300));
    }

    /** A valid token with no {@code tenant_id} claim at all. */
    static String noTenant() {
        return token(TRUSTED, ISSUER, AUDIENCE, null, List.of("OPS_RUN_VIEWER", "OPS_ALERT_SENDER"),
                Instant.now().plusSeconds(300));
    }

    private static String token(KeyPair pair, String issuer, String audience, String tenant, List<String> roles,
                                Instant expires) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("test").build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject("svc-test").issuedAt(expires.minusSeconds(600)).expiresAt(expires).claim("roles", roles);
        if (tenant != null) {
            claims.claim("tenant_id", tenant);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(),
                claims.build())).getTokenValue();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String writePublicKey(KeyPair pair) {
        try {
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            Path file = Files.createTempFile("opsagent-test-public-", ".pem");
            file.toFile().deleteOnExit();
            Files.writeString(file, pem);
            return file.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
