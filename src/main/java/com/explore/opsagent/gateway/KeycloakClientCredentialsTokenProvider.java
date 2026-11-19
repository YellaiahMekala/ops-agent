// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * OAuth2 client-credentials token from the platform Keycloak realm (client {@code ops-agent-gateway}). The token is
 * cached and replaced {@code earlySkew} before it expires, so a call never leaves with a token about to lapse. The
 * secret comes from the environment and is never logged or put in an exception.
 */
public class KeycloakClientCredentialsTokenProvider implements GatewayTokenProvider {

    private final RestClient client;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final Duration earlySkew;
    private String cached;
    private Instant refreshAt = Instant.MIN;

    public KeycloakClientCredentialsTokenProvider(String tokenUri, String clientId, String clientSecret, Duration timeout,
                                                  Duration earlySkew, Clock clock) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.earlySkew = earlySkew;
        this.clock = clock;
    }

    @Override
    public synchronized String token() {
        if (cached == null || !clock.instant().isBefore(refreshAt)) {
            fetch();
        }
        return cached;
    }

    @Override
    public synchronized void invalidate() {
        cached = null;
        refreshAt = Instant.MIN;
    }

    private void fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        JsonNode body;
        try {
            body = client.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON).body(form).retrieve().body(JsonNode.class);
        } catch (RuntimeException e) {
            // Deliberately not chained: a client exception's message can carry the request, and the request has the secret.
            throw new GatewayCallException(GatewayCallException.Kind.UNAVAILABLE, 0, "TOKEN", null);
        }
        String token = body == null ? null : body.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new GatewayCallException(GatewayCallException.Kind.UNAVAILABLE, 0, "TOKEN", null);
        }
        Duration lifetime = Duration.ofSeconds(Math.max(body.path("expires_in").asLong(60), 1));
        Duration use = lifetime.compareTo(earlySkew.multipliedBy(2)) > 0 ? lifetime.minus(earlySkew) : lifetime.dividedBy(2);
        cached = token;
        refreshAt = clock.instant().plus(use);
    }
}
