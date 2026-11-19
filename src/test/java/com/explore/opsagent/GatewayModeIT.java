// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent;

import com.explore.opsagent.gateway.GatewayChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0003 end to end, in gateway mode: the whole application (Postgres and Mailpit in Testcontainers, the real
 * read-only tools on fixtures) against a fake <em>gateway</em> and a fake token endpoint, both local HTTP servers. The
 * fake gateway plays the contract {@code platform/contracts/gateway/tool-calling.v1.md}; nothing else is faked. No
 * provider is reachable from this test and there is no key: the only model endpoint configured is the fake gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Gateway mode: tool-call round trip, 403, 429, 5xx against a fake gateway (ADR-0003, D1.3.1)")
class GatewayModeIT {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String CLIENT_SECRET = "it-client-secret-not-real";

    record Seen(Headers headers, JsonNode body) { }

    record Scripted(int status, String body) { }

    static final HttpServer FAKE;
    static final List<Seen> CHAT = new CopyOnWriteArrayList<>();
    static final List<String> TOKEN_FORMS = new CopyOnWriteArrayList<>();
    static final ConcurrentLinkedQueue<Scripted> SCRIPT = new ConcurrentLinkedQueue<>();
    static final AtomicInteger TOKENS = new AtomicInteger();

    static {
        // Reuse the containers and fixture server GuardrailIT starts once per JVM.
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE.createContext("/token", ex -> {
            TOKEN_FORMS.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "{\"access_token\":\"fake-jwt-" + TOKENS.incrementAndGet() + "\",\"expires_in\":300}");
        });
        FAKE.createContext("/api/v1/chat", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CHAT.add(new Seen(ex.getRequestHeaders(), JSON.readTree(body)));
            Scripted next = SCRIPT.poll();
            if (next == null) {
                reply(ex, 500, "{\"code\":\"UNSCRIPTED\"}");
            } else {
                reply(ex, next.status(), next.body());
            }
        });
        FAKE.start();
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String fixtures = "http://127.0.0.1:" + GuardrailIT.FIXTURES.getAddress().getPort();
        String fake = "http://127.0.0.1:" + FAKE.getAddress().getPort();
        r.add("spring.datasource.url", GuardrailIT.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", GuardrailIT.POSTGRES::getUsername);
        r.add("spring.datasource.password", GuardrailIT.POSTGRES::getPassword);
        r.add("spring.mail.host", GuardrailIT.MAILPIT::getHost);
        r.add("spring.mail.port", () -> GuardrailIT.MAILPIT.getMappedPort(1025));
        r.add("opsagent.webhook.token", () -> GuardrailIT.TOKEN);
        r.add("opsagent.model.mode", () -> "gateway");
        r.add("opsagent.gateway.base-url", () -> fake);
        r.add("opsagent.gateway.token-uri", () -> fake + "/token");
        r.add("opsagent.gateway.client-secret", () -> CLIENT_SECRET);
        r.add("opsagent.sources.prometheus-url", () -> fixtures);
        r.add("opsagent.sources.health.gateway", () -> fixtures + "/actuator/health/readiness");
        r.add("opsagent.sources.log-dir", () -> "src/test/resources/fixtures/logs");
        r.add("opsagent.sources.runbook", () -> "src/test/resources/fixtures/RUNBOOK.md");
        r.add("logging.structured.format.console", () -> "");
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @Autowired
    ChatModel wired;
    @Autowired
    JdbcTemplate jdbc;
    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        CHAT.clear();
        SCRIPT.clear();
    }

    static String toolCall(String id, String tool, String argsJson) {
        return "{\"invocationId\":\"inv-" + id + "\",\"correlationId\":\"c\",\"status\":\"OK\",\"output\":\"\","
                + "\"finishReason\":\"TOOL_CALLS\",\"toolCalls\":[{\"id\":\"" + id + "\",\"name\":\"" + tool
                + "\",\"arguments\":" + JSON.valueToTree(argsJson) + "}],"
                + "\"usage\":{\"inputTokens\":800,\"outputTokens\":40,\"source\":\"PROVIDER\"}}";
    }

    static final String FINAL_ANSWER = "{\"invocationId\":\"inv-final\",\"correlationId\":\"c\",\"status\":\"OK\","
            + "\"output\":\"{\\\"findings\\\":[\\\"runbook read\\\"],\\\"likelyCause\\\":\\\"unknown\\\",\\\"confidence\\\":\\\"LOW\\\"}\","
            + "\"finishReason\":\"STOP\",\"usage\":{\"inputTokens\":900,\"outputTokens\":30,\"source\":\"PROVIDER\"}}";

    String alert() throws IOException {
        String body = Files.readString(Path.of("src/test/resources/fixtures/alerts/provider-error-ratio.json"));
        return body.replace("5a7c2b1d9e0f3a41", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    String post(String alert) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/alerts"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + GuardrailIT.TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(alert)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r.statusCode(), r.body());
        return GuardrailIT.JSON.readTree(alert).at("/alerts/0/fingerprint").asText();
    }

    Map<String, Object> finished(String fingerprint) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM triage_run WHERE fingerprint = ? AND status NOT IN ('RECEIVED', 'RUNNING')", fingerprint);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("run did not finish");
    }

    @Test
    @DisplayName("gateway mode wires the GatewayChatModel: no provider client, no key")
    void wiring() {
        assertTrue(wired instanceof GatewayChatModel);
    }

    @Test
    @DisplayName("round trip: step 1 TOOL_CALLS, the real read-only tool runs here, step 2 STOP; requests match the contract")
    void roundTrip() throws Exception {
        SCRIPT.add(new Scripted(200, toolCall("call_1", "readRunbook", "{\"alertName\":\"GatewayProviderErrorRatio\"}")));
        SCRIPT.add(new Scripted(200, FINAL_ANSWER));

        String fp = post(alert());
        Map<String, Object> run = finished(fp);

        assertEquals("DONE", run.get("status"));
        assertEquals(2, ((Number) run.get("steps")).intValue());
        assertEquals(800 + 900, ((Number) run.get("input_tokens")).intValue(), "provider usage from the gateway");
        assertEquals(40 + 30, ((Number) run.get("output_tokens")).intValue());
        assertEquals(1, ((Number) run.get("tool_calls")).intValue());
        assertEquals(Boolean.TRUE, run.get("mailed"));

        // The tool ran in this process and was audited, like in direct mode.
        List<Map<String, Object>> audit = jdbc.queryForList("SELECT tool, outcome FROM tool_audit WHERE run_id = ?", run.get("id"));
        assertEquals(1, audit.size());
        assertEquals("readRunbook", audit.get(0).get("tool"));
        assertEquals("OK", audit.get(0).get("outcome"));

        assertEquals(2, CHAT.size(), "exactly two gateway calls, nothing else");
        String runId = run.get("id").toString();

        Seen one = CHAT.get(0);
        Seen two = CHAT.get(1);
        for (Seen s : List.of(one, two)) {
            assertTrue(s.headers().getFirst("Authorization").startsWith("Bearer fake-jwt-"));
            assertEquals(runId, s.headers().getFirst("X-Agent-Run-Id"));
            assertEquals("AGENT_TOOL_STEP", s.body().path("taskClass").asText());
            assertEquals(4, s.body().path("tools").size());
            assertEquals("object", s.body().at("/tools/0/parameters/type").asText());
            assertFalse(s.body().has("prompt"));
        }
        assertEquals(runId + ":1", one.headers().getFirst("Idempotency-Key"));
        assertEquals(runId + ":2", two.headers().getFirst("Idempotency-Key"));

        JsonNode m1 = one.body().path("messages");
        assertEquals(List.of("SYSTEM", "USER"), roles(m1));
        JsonNode m2 = two.body().path("messages");
        assertEquals(List.of("SYSTEM", "USER", "ASSISTANT", "TOOL"), roles(m2));
        assertEquals("call_1", m2.get(2).at("/toolCalls/0/id").asText());
        assertEquals("readRunbook", m2.get(2).at("/toolCalls/0/name").asText());
        assertEquals("{\"alertName\":\"GatewayProviderErrorRatio\"}", m2.get(2).at("/toolCalls/0/arguments").asText());
        assertEquals("call_1", m2.get(3).path("toolCallId").asText(), "the TOOL message answers the assistant's call id");
        assertTrue(m2.get(3).path("content").asText().startsWith("<tool-result tool=\"readRunbook\">"),
                "the tool result is sent back wrapped as data");
        assertTrue(m2.get(3).path("content").asText().contains("Raise the retry count"), "the real runbook fixture");

        assertEquals(1, TOKEN_FORMS.size(), "one token for the whole run: cached");
        assertTrue(TOKEN_FORMS.get(0).contains("grant_type=client_credentials"));
        assertTrue(TOKEN_FORMS.get(0).contains("client_id=ops-agent-gateway"));
    }

    @Test
    @DisplayName("403 TOOL_NOT_ALLOWED: non-retryable, the run FAILS once with a named reason, the email still goes")
    void toolNotAllowed() throws Exception {
        SCRIPT.add(new Scripted(403, "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"TOOL_NOT_ALLOWED\"}"));

        Map<String, Object> run = finished(post(alert()));

        assertEquals("FAILED", run.get("status"));
        assertEquals("GATEWAY_TOOL_NOT_ALLOWED", run.get("stop_reason"));
        assertEquals(1, CHAT.size(), "no retry of a refused request");
        assertEquals(Boolean.TRUE, run.get("mailed"));
        assertEquals(0, ((Number) run.get("tool_calls")).intValue());
    }

    @Test
    @DisplayName("429 AGENT_RUN_LIMIT on step 2: the run STOPS cleanly (not FAILED), the tool work so far is kept")
    void runLimit() throws Exception {
        SCRIPT.add(new Scripted(200, toolCall("call_1", "readRunbook", "{\"alertName\":\"GatewayProviderErrorRatio\"}")));
        SCRIPT.add(new Scripted(429, "{\"status\":429,\"code\":\"AGENT_RUN_LIMIT\"}"));

        Map<String, Object> run = finished(post(alert()));

        assertEquals("STOPPED", run.get("status"));
        assertEquals("GATEWAY_RUN_LIMIT", run.get("stop_reason"));
        assertEquals(2, CHAT.size());
        assertEquals(1, ((Number) run.get("tool_calls")).intValue());
        assertEquals(Boolean.TRUE, run.get("mailed"));
    }

    @Test
    @DisplayName("5xx: the run FAILS as unavailable and the step is not redone (no second call)")
    void serverError() throws Exception {
        SCRIPT.add(new Scripted(503, "{\"code\":\"PROVIDER_UNAVAILABLE\"}"));

        Map<String, Object> run = finished(post(alert()));

        assertEquals("FAILED", run.get("status"));
        assertEquals("GATEWAY_UNAVAILABLE", run.get("stop_reason"));
        assertEquals(1, CHAT.size(), "ADR-0001: a call that may have happened is not redone");
        assertNotNull(run.get("finished_at"));
    }

    static List<String> roles(JsonNode messages) {
        List<String> out = new java.util.ArrayList<>();
        messages.forEach(m -> out.add(m.path("role").asText()));
        return out;
    }
}
