// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ADR-0002 migration step 3: with {@code legacy-enabled=false} the shared token is just a wrong bearer value. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Legacy webhook token switched off (ADR-0002)")
class LegacyDisabledIT {

    static final String SHARED = "test-webhook-token-not-a-secret";
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.mail.host", () -> "127.0.0.1");
        r.add("spring.mail.port", () -> "1");
        r.add("opsagent.webhook.token", () -> SHARED); // still set, but not honoured
        r.add("opsagent.webhook.legacy-enabled", () -> "false");
        r.add("opsagent.model.api-key", () -> "");
        r.add("opsagent.security.issuer", () -> TestJwt.ISSUER);
        r.add("opsagent.security.public-key-location", TestJwt::publicKeyLocation);
        r.add("opsagent.sources.log-dir", () -> "src/test/resources/fixtures/logs");
        r.add("opsagent.sources.runbook", () -> "src/test/resources/fixtures/RUNBOOK.md");
        r.add("logging.structured.format.console", () -> "");
    }

    @TestConfiguration
    static class Doubles {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @LocalServerPort
    int port;

    HttpResponse<String> post(String token) throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/alerts/provider-error-ratio.json"));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/alerts"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("the shared token is 401, a JWT sender is 202")
    void sharedTokenRefused() throws Exception {
        assertEquals(401, post(SHARED).statusCode());
        assertEquals(202, post(TestJwt.sender("tenant-a")).statusCode());
    }
}
