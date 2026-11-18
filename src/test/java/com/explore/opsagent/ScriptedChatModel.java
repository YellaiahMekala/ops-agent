package com.explore.opsagent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the model (plan 12.5): replays a script of tool calls and a final answer, and records the tool results
 * it was shown. Every step reports a fixed usage, so token limits can be tested. With {@link #loopForever()} it asks
 * for the same tool again on every step, which is what a runaway agent looks like.
 */
public class ScriptedChatModel implements ChatModel {

    public static final int IN_PER_STEP = 500;
    public static final int OUT_PER_STEP = 50;

    private final ConcurrentLinkedQueue<AiMessage> script = new ConcurrentLinkedQueue<>();
    private final List<String> toolResultsSeen = new CopyOnWriteArrayList<>();
    private volatile boolean loop;
    private volatile long delayMillis;
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    public void reset() {
        script.clear();
        toolResultsSeen.clear();
        loop = false;
        delayMillis = 0;
        calls.set(0);
    }

    /** Model calls made since the last reset. */
    public int calls() {
        return calls.get();
    }

    public ScriptedChatModel tool(String name, String argumentsJson) {
        script.add(AiMessage.from(ToolExecutionRequest.builder().id("call-" + UUID.randomUUID()).name(name)
                .arguments(argumentsJson).build()));
        return this;
    }

    public ScriptedChatModel answer(String text) {
        script.add(AiMessage.from(text));
        return this;
    }

    public ScriptedChatModel loopForever() {
        loop = true;
        return this;
    }

    public ScriptedChatModel slow(long millis) {
        delayMillis = millis;
        return this;
    }

    public List<String> toolResultsSeen() {
        return List.copyOf(toolResultsSeen);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        calls.incrementAndGet();
        List<ChatMessage> messages = request.messages();
        if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof ToolExecutionResultMessage r) {
            toolResultsSeen.add(r.text());
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
        AiMessage next;
        if (loop) {
            next = AiMessage.from(ToolExecutionRequest.builder().id("call-" + UUID.randomUUID()).name("readRunbook")
                    .arguments("{\"alertName\":\"GatewayProviderErrorRatio\"}").build());
        } else {
            next = script.poll();
            if (next == null) {
                next = AiMessage.from("{\"findings\":[],\"likelyCause\":\"unknown\",\"confidence\":\"LOW\"}");
            }
        }
        return ChatResponse.builder().aiMessage(next).tokenUsage(new TokenUsage(IN_PER_STEP, OUT_PER_STEP))
                .finishReason(next.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP).build();
    }
}
