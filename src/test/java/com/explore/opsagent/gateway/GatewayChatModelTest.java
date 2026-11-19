package com.explore.opsagent.gateway;

import com.explore.opsagent.tools.OpsTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mapping both ways, error mapping, and the token cache, against a throwaway local HTTP server (no gateway, no key). */
@DisplayName("GatewayChatModel: contract mapping, errors, token cache (D1.3.1)")
class GatewayChatModelTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String RUN = "5b0e6a3c-0000-4000-8000-00000000abcd";

    HttpServer server;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<com.sun.net.httpserver.Headers> headers = new CopyOnWriteArrayList<>();
    final AtomicReference<int[]> status = new AtomicReference<>(new int[] {200});
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicInteger tokenCalls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.add(ex.getRequestHeaders());
            send(ex, status.get()[0], reply.get());
        });
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            send(ex, 200, "{\"access_token\":\"tok-" + tokenCalls.incrementAndGet() + "\",\"expires_in\":300}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    GatewayChatModel model() {
        GatewayTokenProvider tokens = new GatewayTokenProvider() {
            public String token() {
                return "tok-fixed";
            }

            public void invalidate() {
            }
        };
        return new GatewayChatModel(url(), tokens, () -> RUN, JSON, Duration.ofSeconds(5), 600, 0.0);
    }

    static List<ToolSpecification> tools() {
        return ToolSpecifications.toolSpecificationsFrom(OpsTools.class);
    }

    static ChatRequest firstRequest() {
        return ChatRequest.builder().messages(SystemMessage.from("sys"), UserMessage.from("Alert X"))
                .toolSpecifications(tools()).build();
    }

    static ChatRequest secondRequest() {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("call_1").name("readRunbook")
                .arguments("{\"alertName\":\"X\"}").build();
        return ChatRequest.builder().messages(SystemMessage.from("sys"), UserMessage.from("Alert X"),
                AiMessage.from(call), ToolExecutionResultMessage.from(call, "runbook text")).toolSpecifications(tools()).build();
    }

    static final String STOP = "{\"invocationId\":\"i1\",\"status\":\"OK\",\"output\":\"done\",\"finishReason\":\"STOP\","
            + "\"usage\":{\"inputTokens\":10,\"outputTokens\":3,\"source\":\"PROVIDER\"}}";

    @Test
    @DisplayName("request: roles, tool calls and results, tools with JSON-schema parameters, task class, settings")
    void requestBody() throws Exception {
        reply.set(STOP);
        model().chat(secondRequest());

        JsonNode body = JSON.readTree(bodies.get(0));
        assertEquals("AGENT_TOOL_STEP", body.path("taskClass").asText());
        JsonNode m = body.path("messages");
        assertEquals(4, m.size());
        assertEquals("SYSTEM", m.get(0).path("role").asText());
        assertEquals("sys", m.get(0).path("content").asText());
        assertEquals("USER", m.get(1).path("role").asText());
        assertEquals("ASSISTANT", m.get(2).path("role").asText());
        assertTrue(m.get(2).path("content").isNull(), "content is null when the assistant only called tools");
        assertEquals("call_1", m.get(2).at("/toolCalls/0/id").asText());
        assertEquals("readRunbook", m.get(2).at("/toolCalls/0/name").asText());
        assertTrue(m.get(2).at("/toolCalls/0/arguments").isTextual(), "arguments is a JSON string");
        assertEquals("{\"alertName\":\"X\"}", m.get(2).at("/toolCalls/0/arguments").asText());
        assertEquals("TOOL", m.get(3).path("role").asText());
        assertEquals("call_1", m.get(3).path("toolCallId").asText());
        assertEquals("runbook text", m.get(3).path("content").asText());

        JsonNode t = body.path("tools");
        assertEquals(4, t.size());
        JsonNode prom = null;
        for (JsonNode tool : t) {
            assertTrue(tool.path("name").asText().matches("^[A-Za-z0-9_-]{1,64}$"));
            assertEquals("object", tool.at("/parameters/type").asText());
            assertTrue(tool.path("description").asText().length() <= 1024);
            if ("queryPrometheus".equals(tool.path("name").asText())) {
                prom = tool;
            }
        }
        assertNotNull(prom);
        assertEquals("string", prom.at("/parameters/properties/promql/type").asText());
        assertEquals("promql", prom.at("/parameters/required/0").asText());
        assertEquals(600, body.at("/settings/maxOutputTokens").asInt());
        assertEquals(0.0, body.at("/settings/temperature").asDouble());
        assertFalse(body.has("prompt"), "messages input only; prompt plus tools is a 400");
    }

    @Test
    @DisplayName("headers: bearer, Idempotency-Key <runId>:<step> from the conversation, X-Agent-Run-Id")
    void headersAndStep() {
        reply.set(STOP);
        model().chat(firstRequest());
        model().chat(secondRequest());

        assertEquals("Bearer tok-fixed", headers.get(0).getFirst("Authorization"));
        assertEquals(RUN + ":1", headers.get(0).getFirst("Idempotency-Key"));
        assertEquals(RUN + ":2", headers.get(1).getFirst("Idempotency-Key"));
        assertEquals(RUN, headers.get(0).getFirst("X-Agent-Run-Id"));
        assertEquals(RUN, headers.get(1).getFirst("X-Agent-Run-Id"));
    }

    @Test
    @DisplayName("response TOOL_CALLS: tool calls become execution requests, finish reason and usage are mapped")
    void toolCallResponse() {
        reply.set("{\"invocationId\":\"i2\",\"status\":\"OK\",\"output\":\"\",\"finishReason\":\"TOOL_CALLS\","
                + "\"toolCalls\":[{\"id\":\"call_2\",\"name\":\"searchLogs\",\"arguments\":\"{\\\"level\\\":\\\"ERROR\\\"}\"}],"
                + "\"usage\":{\"inputTokens\":812,\"outputTokens\":41,\"source\":\"PROVIDER\"}}");
        ChatResponse r = model().chat(firstRequest());

        assertEquals(FinishReason.TOOL_EXECUTION, r.finishReason());
        assertTrue(r.aiMessage().hasToolExecutionRequests());
        ToolExecutionRequest call = r.aiMessage().toolExecutionRequests().get(0);
        assertEquals("call_2", call.id());
        assertEquals("searchLogs", call.name());
        assertEquals("{\"level\":\"ERROR\"}", call.arguments());
        assertEquals(812, r.tokenUsage().inputTokenCount());
        assertEquals(41, r.tokenUsage().outputTokenCount());
    }

    @Test
    @DisplayName("response STOP: the output is the answer, no tool calls")
    void stopResponse() {
        reply.set(STOP);
        ChatResponse r = model().chat(firstRequest());
        assertEquals(FinishReason.STOP, r.finishReason());
        assertEquals("done", r.aiMessage().text());
        assertFalse(r.aiMessage().hasToolExecutionRequests());
    }

    @Test
    @DisplayName("the gateway's real success statuses (OK first) are accepted; a failure status is not")
    void successStatuses() {
        for (String status : List.of("OK", "FALLBACK_OK", "SUCCEEDED")) {
            reply.set("{\"status\":\"" + status + "\",\"output\":\"done\",\"finishReason\":\"STOP\"}");
            assertEquals("done", model().chat(firstRequest()).aiMessage().text(), status);
        }
        reply.set("{\"status\":\"FAILED\",\"output\":\"done\",\"finishReason\":\"STOP\"}");
        assertThrows(GatewayCallException.class, () -> model().chat(firstRequest()));
    }

    @Test
    @DisplayName("an IDEMPOTENT_REPLAY of a tool step has no toolCalls: a bad response, the run fails, nothing is redone")
    void idempotentReplayWithoutToolCalls() {
        reply.set("{\"status\":\"IDEMPOTENT_REPLAY\",\"output\":\"\",\"finishReason\":\"TOOL_CALLS\"}");
        GatewayCallException e = assertThrows(GatewayCallException.class, () -> model().chat(firstRequest()));
        assertEquals(GatewayCallException.Kind.UNAVAILABLE, e.kind());
    }

    @Test
    @DisplayName("TOOL_CALLS without toolCalls is a bad response, not a silent empty answer")
    void toolCallsMissing() {
        reply.set("{\"status\":\"OK\",\"output\":\"\",\"finishReason\":\"TOOL_CALLS\"}");
        GatewayCallException e = assertThrows(GatewayCallException.class, () -> model().chat(firstRequest()));
        assertEquals(GatewayCallException.Kind.UNAVAILABLE, e.kind());
    }

    @Test
    @DisplayName("a request with no tools is refused locally: AGENT_TOOL_STEP requires tools")
    void noTools() {
        ChatRequest noTools = ChatRequest.builder().messages(UserMessage.from("hi")).build();
        assertThrows(IllegalStateException.class, () -> model().chat(noTools));
        assertTrue(bodies.isEmpty());
    }

    @Test
    @DisplayName("errors: 403 TOOL_NOT_ALLOWED, 400, 422, 401 refused; 429 AGENT_RUN_LIMIT is a clean stop; 5xx and other 429 unavailable")
    void errorMapping() {
        record Case(int status, String body, GatewayCallException.Kind kind, String reason) { }
        List<Case> cases = new ArrayList<>(List.of(
                new Case(403, "{\"code\":\"TOOL_NOT_ALLOWED\",\"detail\":\"secret-looking-detail\"}",
                        GatewayCallException.Kind.REJECTED, "GATEWAY_TOOL_NOT_ALLOWED"),
                new Case(400, "{\"code\":\"INVALID_TOOLS\"}", GatewayCallException.Kind.REJECTED, "GATEWAY_INVALID_TOOLS"),
                new Case(422, "{\"code\":\"TOOLS_NOT_SUPPORTED\"}", GatewayCallException.Kind.REJECTED, "GATEWAY_TOOLS_NOT_SUPPORTED"),
                new Case(401, "", GatewayCallException.Kind.REJECTED, "GATEWAY_HTTP_401"),
                new Case(429, "{\"code\":\"AGENT_RUN_LIMIT\"}", GatewayCallException.Kind.RUN_LIMIT, "GATEWAY_RUN_LIMIT"),
                new Case(429, "{\"code\":\"RATE_LIMITED\"}", GatewayCallException.Kind.UNAVAILABLE, "GATEWAY_UNAVAILABLE"),
                new Case(503, "<html>down</html>", GatewayCallException.Kind.UNAVAILABLE, "GATEWAY_UNAVAILABLE"),
                new Case(500, "{\"code\":\"INTERNAL\"}", GatewayCallException.Kind.UNAVAILABLE, "GATEWAY_UNAVAILABLE")));
        for (Case c : cases) {
            status.set(new int[] {c.status});
            reply.set(c.body);
            GatewayCallException e = assertThrows(GatewayCallException.class, () -> model().chat(firstRequest()), c.toString());
            assertEquals(c.kind, e.kind(), c.toString());
            assertEquals(c.reason, e.stopReason(), c.toString());
            assertFalse(String.valueOf(e.getMessage()).contains("secret-looking-detail"), "no response body in the error");
        }
    }

    @Test
    @DisplayName("a connection failure is UNAVAILABLE (outcome unknown), never a rejection")
    void connectionFailure() {
        int port = server.getAddress().getPort();
        server.stop(0);
        GatewayChatModel dead = new GatewayChatModel("http://127.0.0.1:" + port, new GatewayTokenProvider() {
            public String token() {
                return "t";
            }

            public void invalidate() {
            }
        }, () -> RUN, JSON, Duration.ofSeconds(2), 600, 0.0);
        GatewayCallException e = assertThrows(GatewayCallException.class, () -> dead.chat(firstRequest()));
        assertEquals(GatewayCallException.Kind.UNAVAILABLE, e.kind());
    }

    @Test
    @DisplayName("token: client-credentials, cached until shortly before expiry, then refreshed")
    void tokenCache() {
        MutableNow now = new MutableNow(Instant.parse("2026-11-19T10:00:00Z"));
        KeycloakClientCredentialsTokenProvider p = new KeycloakClientCredentialsTokenProvider(url() + "/token",
                "ops-agent-gateway", "not-a-real-secret", Duration.ofSeconds(5), Duration.ofSeconds(30), now);

        assertEquals("tok-1", p.token());
        assertEquals("tok-1", p.token());
        now.t = now.t.plusSeconds(200); // 270s lifetime used of 300 minus 30 skew: still cached
        assertEquals("tok-1", p.token());
        now.t = now.t.plusSeconds(71); // 271s > 270s: refreshed
        assertEquals("tok-2", p.token());
        assertEquals(2, tokenCalls.get());
        p.invalidate();
        assertEquals("tok-3", p.token());
    }

    @Test
    @DisplayName("token endpoint failure: UNAVAILABLE, and the secret is not in the exception")
    void tokenFailure() {
        KeycloakClientCredentialsTokenProvider p = new KeycloakClientCredentialsTokenProvider(url() + "/nope",
                "ops-agent-gateway", "not-a-real-secret", Duration.ofSeconds(5), Duration.ofSeconds(30), Clock.systemUTC());
        GatewayCallException e = assertThrows(GatewayCallException.class, p::token);
        assertEquals(GatewayCallException.Kind.UNAVAILABLE, e.kind());
        assertFalse(e.toString().contains("not-a-real-secret"));
        assertEquals(null, e.getCause());
    }

    static final class MutableNow extends Clock {
        Instant t;

        MutableNow(Instant t) {
            this.t = t;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return t;
        }
    }
}
