// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0002 end to end against real Postgres: 401 without a valid token, 403 for the wrong role, tenant isolation on
 * every read, the legacy shared token still working, the dedupe key including the tenant, and the malformed-JSON 400
 * unchanged. Tokens are minted per run with throwaway keys (see {@link TestJwt}); the model is scripted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("JWT and tenant model: 401, 403, tenant isolation, legacy token, tenant-scoped dedupe (ADR-0002)")
class TenantSecurityIT {

    static final String LEGACY_TOKEN = "test-webhook-token-not-a-secret";
    static final ObjectMapper JSON = new ObjectMapper();
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
        r.add("spring.mail.port", () -> "1"); // nothing listens: a mail failure is logged, the rows are what is tested
        r.add("opsagent.webhook.token", () -> LEGACY_TOKEN);
        r.add("opsagent.webhook.queue-capacity", () -> "32");
        r.add("opsagent.model.api-key", () -> "");
        r.add("opsagent.security.issuer", () -> TestJwt.ISSUER);
        r.add("opsagent.security.public-key-location", TestJwt::publicKeyLocation);
        r.add("opsagent.sources.log-dir", () -> "src/test/resources/fixtures/logs");
        r.add("opsagent.sources.runbook", () -> "src/test/resources/fixtures/RUNBOOK.md");
        r.add("opsagent.guardrails.timeout", () -> "5s");
        r.add("logging.structured.format.console", () -> "");
    }

    @TestConfiguration
    static class Doubles {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    static String alert(String fingerprint) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/alerts/provider-error-ratio.json"))
                .replace("5a7c2b1d9e0f3a41", fingerprint);
    }

    static String fingerprint() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String token, String body) throws Exception {
        return send("POST", "/alerts", token, body);
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    UUID runId(String tenant, String fp) {
        return jdbc.queryForObject("SELECT id FROM triage_run WHERE tenant_id = ? AND fingerprint = ?", UUID.class,
                tenant, fp);
    }

    @Test
    @DisplayName("no token is 401 with a code and no detail, on the webhook and on the read endpoints")
    void noToken() throws Exception {
        HttpResponse<String> hook = post(null, alert(fingerprint()));
        assertEquals(401, hook.statusCode());
        assertEquals("{\"code\":\"UNAUTHORIZED\"}", hook.body());
        assertEquals(401, get("/api/v1/runs", null).statusCode());
        assertEquals(401, get("/api/v1/runs/" + UUID.randomUUID(), null).statusCode());
    }

    @Test
    @DisplayName("tokens that fail validation are 401: forged, expired, wrong audience, wrong issuer, no tenant_id, garbage")
    void badTokens() throws Exception {
        for (String bad : List.of(TestJwt.forged("tenant-a"), TestJwt.expired("tenant-a"),
                TestJwt.wrongAudience("tenant-a"), TestJwt.wrongIssuer("tenant-a"), TestJwt.noTenant(), "not-a-jwt")) {
            assertEquals(401, post(bad, alert(fingerprint())).statusCode());
            assertEquals(401, get("/api/v1/runs", bad).statusCode());
        }
    }

    @Test
    @DisplayName("the wrong role is 403: a viewer cannot send alerts, a sender cannot read runs, no role reads nothing")
    void wrongRole() throws Exception {
        String fp = fingerprint();
        HttpResponse<String> viewerPost = post(TestJwt.viewer("tenant-a"), alert(fp));
        assertEquals(403, viewerPost.statusCode());
        assertEquals("{\"code\":\"FORBIDDEN\"}", viewerPost.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM triage_run WHERE fingerprint = ?", Integer.class, fp),
                "a refused request starts nothing");
        assertEquals(403, get("/api/v1/runs", TestJwt.sender("tenant-a")).statusCode());
        assertEquals(403, get("/api/v1/runs", TestJwt.withRoles("tenant-a", List.of("SOMETHING_ELSE"))).statusCode());
        assertEquals(403, get("/api/v1/runs", TestJwt.withRoles("tenant-a", List.of())).statusCode());
    }

    @Test
    @DisplayName("tenant A cannot read tenant B: list, by id, and a tenant header is ignored")
    void tenantIsolation() throws Exception {
        String fpA = fingerprint();
        String fpB = fingerprint();
        assertEquals(202, post(TestJwt.sender("tenant-a"), alert(fpA)).statusCode());
        assertEquals(202, post(TestJwt.sender("tenant-b"), alert(fpB)).statusCode());
        UUID runA = runId("tenant-a", fpA);
        UUID runB = runId("tenant-b", fpB);

        JsonNode listA = JSON.readTree(get("/api/v1/runs?limit=100", TestJwt.viewer("tenant-a")).body());
        List<String> idsA = listA.path("runs").findValuesAsText("id");
        assertTrue(idsA.contains(runA.toString()));
        assertFalse(idsA.contains(runB.toString()), "tenant A's list has none of tenant B's runs");
        JsonNode listB = JSON.readTree(get("/api/v1/runs?limit=100", TestJwt.viewer("tenant-b")).body());
        assertFalse(listB.path("runs").findValuesAsText("id").contains(runA.toString()));

        HttpResponse<String> own = get("/api/v1/runs/" + runA, TestJwt.viewer("tenant-a"));
        assertEquals(200, own.statusCode());
        assertEquals(fpA, JSON.readTree(own.body()).at("/run/fingerprint").asText());
        assertFalse(own.body().contains("alert_payload") || own.body().contains("lease"), "no internals in the view");

        HttpResponse<String> cross = get("/api/v1/runs/" + runB, TestJwt.viewer("tenant-a"));
        assertEquals(404, cross.statusCode());
        assertEquals("{\"code\":\"NOT_FOUND\"}", cross.body());
        assertEquals(404, get("/api/v1/runs/" + UUID.randomUUID(), TestJwt.viewer("tenant-a")).statusCode(),
                "same answer as a run that does not exist");

        // A header or parameter cannot pick a tenant.
        HttpRequest spoof = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/" + runB
                + "?tenant_id=tenant-b")).header("Authorization", "Bearer " + TestJwt.viewer("tenant-a"))
                .header("X-Tenant-Id", "tenant-b").GET().build();
        assertEquals(404, http.send(spoof, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    @DisplayName("the dedupe key includes the tenant: same incident twice for A is DUPLICATE, for B it is its own run")
    void dedupeIncludesTenant() throws Exception {
        String fp = fingerprint();
        String body = alert(fp);
        assertTrue(post(TestJwt.sender("tenant-a"), body).body().contains("STARTED"));
        assertTrue(post(TestJwt.sender("tenant-a"), body).body().contains("DUPLICATE"));
        assertTrue(post(TestJwt.sender("tenant-b"), body).body().contains("STARTED"), "another tenant, another incident");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM triage_run WHERE fingerprint = ?", Integer.class, fp));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(DISTINCT tenant_id) FROM triage_run WHERE fingerprint = ?", Integer.class, fp));
    }

    @Test
    @DisplayName("legacy mode: the shared token still sends alerts, as tenant 'legacy', and opens nothing else")
    void legacyWebhookStillWorks() throws Exception {
        String fp = fingerprint();
        HttpResponse<String> sent = post(LEGACY_TOKEN, alert(fp));
        assertEquals(202, sent.statusCode(), sent.body());
        assertTrue(sent.body().contains("STARTED"));
        assertEquals("legacy", jdbc.queryForObject("SELECT tenant_id FROM triage_run WHERE fingerprint = ?",
                String.class, fp));
        assertEquals("alertmanager", jdbc.queryForObject("SELECT caller FROM triage_run WHERE fingerprint = ?",
                String.class, fp));
        assertTrue(post(LEGACY_TOKEN, alert(fp)).body().contains("DUPLICATE"));

        assertEquals(401, post("wrong-token", alert(fingerprint())).statusCode());
        assertEquals(401, get("/api/v1/runs", LEGACY_TOKEN).statusCode(), "the shared token reads nothing");
        // A JWT viewer of the legacy tenant can read what the legacy webhook made.
        JsonNode list = JSON.readTree(get("/api/v1/runs?limit=100", TestJwt.viewer("legacy")).body());
        assertTrue(list.path("runs").findValuesAsText("fingerprint").contains(fp));
        assertFalse(JSON.readTree(get("/api/v1/runs?limit=100", TestJwt.viewer("tenant-a")).body())
                .path("runs").findValuesAsText("fingerprint").contains(fp));
    }

    @Test
    @DisplayName("a JWT sender and the legacy token both reach the same validation: malformed JSON is still 400 without an echo")
    void malformedJsonStill400() throws Exception {
        String garbage = "{\"version\": \"4\", secret-value-123 [";
        for (String token : List.of(TestJwt.sender("tenant-a"), LEGACY_TOKEN)) {
            HttpResponse<String> bad = post(token, garbage);
            assertEquals(400, bad.statusCode());
            assertTrue(bad.body().contains("INVALID_ALERT"));
            assertFalse(bad.body().contains("secret-value-123"), "the input is not echoed");
        }
        // Authentication comes first: no token and a bad body is 401, not 400.
        assertEquals(401, post(null, garbage).statusCode());
    }
}
