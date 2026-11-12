package com.explore.opsagent.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the real model for one run and stops it at the first limit reached (plan 12.3): model calls (steps), tokens
 * across those calls, and the run's deadline. The check is before each call, so a run never starts a step it may not
 * finish; the token count is the provider's reported usage, added after each call.
 * <p>
 * One instance per run: the counters are the run's.
 */
public class GuardedChatModel implements ChatModel {

    /** Why a run was stopped. The value goes in the run row and the email; it never carries model text. */
    public enum Limit { STEPS, TOKENS, DEADLINE }

    public static class LimitReached extends RuntimeException {
        private final Limit limit;

        public LimitReached(Limit limit) {
            super("guardrail reached: " + limit, null, false, false);
            this.limit = limit;
        }

        public Limit limit() {
            return limit;
        }
    }

    private final ChatModel delegate;
    private final int maxSteps;
    private final int maxTokens;
    private final Instant deadline;
    private final Clock clock;
    private final AtomicInteger steps = new AtomicInteger();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();

    public GuardedChatModel(ChatModel delegate, int maxSteps, int maxTokens, Instant deadline, Clock clock) {
        this.delegate = delegate;
        this.maxSteps = maxSteps;
        this.maxTokens = maxTokens;
        this.deadline = deadline;
        this.clock = clock;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        if (!clock.instant().isBefore(deadline)) {
            throw new LimitReached(Limit.DEADLINE);
        }
        if (steps.get() >= maxSteps) {
            throw new LimitReached(Limit.STEPS);
        }
        if (inputTokens.get() + outputTokens.get() >= maxTokens) {
            throw new LimitReached(Limit.TOKENS);
        }
        steps.incrementAndGet();
        ChatResponse response = delegate.chat(request);
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            inputTokens.addAndGet(usage.inputTokenCount() == null ? 0 : usage.inputTokenCount());
            outputTokens.addAndGet(usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
        }
        return response;
    }

    public int steps() {
        return steps.get();
    }

    public int inputTokens() {
        return inputTokens.get();
    }

    public int outputTokens() {
        return outputTokens.get();
    }
}
