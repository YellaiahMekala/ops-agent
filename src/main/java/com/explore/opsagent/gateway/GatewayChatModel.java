// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The model, reached through the ai-gateway's {@code POST /api/v1/chat} with {@code taskClass AGENT_TOOL_STEP}
 * (contract {@code platform/contracts/gateway/tool-calling.v1.md}, ADR-0003). The gateway forwards the tool
 * definitions and returns the model's tool calls; it never runs a tool. The tools run here, in this process.
 * <p>
 * Each call carries {@code Idempotency-Key: <runId>:<step>} and {@code X-Agent-Run-Id: <runId>}. The step is derived
 * from the request itself (the number of assistant messages already in it, plus one), so the key is stable for a given
 * conversation position and needs no counter.
 * <p>
 * No retries here, as with the direct client (ADR-0001): a call that fails with a 5xx or a timeout may have happened,
 * so the run ends and is never redone. A failure surfaces as {@link GatewayCallException}.
 */
public class GatewayChatModel implements ChatModel {

    public static final String TASK_CLASS = "AGENT_TOOL_STEP";
    /**
     * Statuses the gateway's chat-response schema uses for a completed call (ChatResponse v1). The first real run
     * showed the gateway answers {@code OK}; the contract example's {@code SUCCEEDED} is kept as accepted, not sent.
     * An {@code IDEMPOTENT_REPLAY} of a tool step carries no tool calls and is rejected below as a bad response.
     */
    static final java.util.Set<String> SUCCESS_STATUSES = java.util.Set.of("OK", "FALLBACK_OK", "CACHE_HIT_EXACT",
            "CACHE_HIT_SEMANTIC", "CACHE_DEGRADED", "IDEMPOTENT_REPLAY", "SUCCEEDED");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,23}");

    private final RestClient client;
    private final GatewayTokenProvider tokens;
    private final Supplier<String> runId;
    private final ObjectMapper json;
    private final int maxOutputTokens;
    private final double temperature;

    public GatewayChatModel(String baseUrl, GatewayTokenProvider tokens, Supplier<String> runId, ObjectMapper json,
                            Duration timeout, int maxOutputTokens, double temperature) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
        this.runId = runId;
        this.json = json;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String run = runId.get();
        int step = step(request.messages());
        ObjectNode body = toBody(request);
        String token = tokens.token();
        JsonNode response;
        try {
            response = client.post().uri("/api/v1/chat")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", run + ":" + step)
                    .header("X-Agent-Run-Id", run)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .exchange((req, res) -> {
                        HttpStatusCode status = res.getStatusCode();
                        byte[] bytes = res.getBody().readAllBytes();
                        if (status.is2xxSuccessful()) {
                            return json.readTree(bytes);
                        }
                        throw failure(status.value(), bytes);
                    });
        } catch (RestClientException e) {
            // Timeout or connection failure: the request may have reached the gateway, so the outcome is unknown.
            // Not chained: the message can carry the URL and headers.
            throw new GatewayCallException(GatewayCallException.Kind.UNAVAILABLE, 0, "IO", null);
        }
        return toResponse(response);
    }

    /** Step 1 is the first model call of a run; each assistant message already in the conversation is a step done. */
    static int step(List<ChatMessage> messages) {
        return (int) messages.stream().filter(m -> m instanceof AiMessage).count() + 1;
    }

    ObjectNode toBody(ChatRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("taskClass", TASK_CLASS);
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            messages.add(toMessage(m));
        }
        List<ToolSpecification> specs = request.toolSpecifications();
        if (specs == null || specs.isEmpty()) {
            throw new IllegalStateException("AGENT_TOOL_STEP requires tools; the request has none");
        }
        ArrayNode tools = body.putArray("tools");
        for (ToolSpecification spec : specs) {
            ObjectNode t = tools.addObject();
            t.put("name", spec.name());
            t.put("description", spec.description() == null ? "" : spec.description());
            if (spec.parameters() == null) {
                ObjectNode empty = json.createObjectNode();
                empty.put("type", "object");
                empty.putObject("properties");
                t.set("parameters", empty);
            } else {
                t.set("parameters", json.valueToTree(JsonSchemaElementUtils.toMap(spec.parameters())));
            }
        }
        ObjectNode settings = body.putObject("settings");
        Integer max = request.maxOutputTokens();
        settings.put("maxOutputTokens", max != null ? max : maxOutputTokens);
        Double temp = request.temperature();
        settings.put("temperature", temp != null ? temp : temperature);
        return body;
    }

    private ObjectNode toMessage(ChatMessage m) {
        ObjectNode n = json.createObjectNode();
        if (m instanceof SystemMessage s) {
            n.put("role", "SYSTEM").put("content", s.text());
        } else if (m instanceof UserMessage u) {
            if (!u.hasSingleText()) {
                throw new IllegalStateException("only text user messages are supported");
            }
            n.put("role", "USER").put("content", u.singleText());
        } else if (m instanceof AiMessage a) {
            n.put("role", "ASSISTANT");
            if (a.text() == null || a.text().isEmpty()) {
                n.putNull("content");
            } else {
                n.put("content", a.text());
            }
            if (a.hasToolExecutionRequests()) {
                ArrayNode calls = n.putArray("toolCalls");
                for (ToolExecutionRequest r : a.toolExecutionRequests()) {
                    calls.addObject().put("id", r.id()).put("name", r.name())
                            .put("arguments", r.arguments() == null ? "{}" : r.arguments());
                }
            }
        } else if (m instanceof ToolExecutionResultMessage t) {
            n.put("role", "TOOL").put("toolCallId", t.id()).put("content", t.text());
        } else {
            throw new IllegalStateException("unsupported message type " + m.type());
        }
        return n;
    }

    ChatResponse toResponse(JsonNode r) {
        if (r == null || !SUCCESS_STATUSES.contains(r.path("status").asText())) {
            throw new GatewayCallException(GatewayCallException.Kind.UNAVAILABLE, 200, "BAD_RESPONSE", null);
        }
        String finish = r.path("finishReason").asText("OTHER");
        String output = r.path("output").asText("");
        AiMessage.Builder ai = AiMessage.builder();
        if (!output.isEmpty()) {
            ai.text(output);
        }
        if ("TOOL_CALLS".equals(finish)) {
            JsonNode calls = r.path("toolCalls");
            if (!calls.isArray() || calls.isEmpty()) {
                throw new GatewayCallException(GatewayCallException.Kind.UNAVAILABLE, 200, "BAD_RESPONSE", null);
            }
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (JsonNode c : calls) {
                requests.add(ToolExecutionRequest.builder().id(c.path("id").asText()).name(c.path("name").asText())
                        .arguments(c.path("arguments").asText("{}")).build());
            }
            ai.toolExecutionRequests(requests);
        }
        JsonNode u = r.path("usage");
        TokenUsage usage = new TokenUsage(u.path("inputTokens").asInt(0), u.path("outputTokens").asInt(0));
        return ChatResponse.builder().id(r.path("invocationId").asText(null)).aiMessage(ai.build()).tokenUsage(usage)
                .finishReason(finishReason(finish)).build();
    }

    static FinishReason finishReason(String gateway) {
        return switch (gateway) {
            case "STOP" -> FinishReason.STOP;
            case "LENGTH" -> FinishReason.LENGTH;
            case "CONTENT_FILTER" -> FinishReason.CONTENT_FILTER;
            case "TOOL_CALLS" -> FinishReason.TOOL_EXECUTION;
            default -> FinishReason.OTHER;
        };
    }

    /** Maps an error response to what the run does next. Reads the problem {@code code}; never keeps the body. */
    GatewayCallException failure(int status, byte[] bodyBytes) {
        String code = null;
        try {
            code = json.readTree(bodyBytes).path("code").asText(null);
        } catch (Exception ignored) {
            // not problem+json: the status alone decides
        }
        if (code == null || !CODE.matcher(code).matches()) {
            code = "HTTP_" + status;
        }
        if (status == 429 && "AGENT_RUN_LIMIT".equals(code)) {
            return new GatewayCallException(GatewayCallException.Kind.RUN_LIMIT, status, code, null);
        }
        if (status == 401) {
            tokens.invalidate();
        }
        if (status >= 500 || status == 429 || status == 408) {
            return new GatewayCallException(GatewayCallException.Kind.UNAVAILABLE, status, code, null);
        }
        return new GatewayCallException(GatewayCallException.Kind.REJECTED, status, code, null);
    }
}
