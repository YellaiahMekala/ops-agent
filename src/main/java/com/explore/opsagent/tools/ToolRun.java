// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.tools;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The run a tool call belongs to: its id, who caused it (the RBAC role) and how many tool calls it has made. Set on the
 * thread that runs the agent, for the length of one run; tools execute on that thread (no concurrent tool execution).
 */
public final class ToolRun {

    private static final ThreadLocal<ToolRun> CURRENT = new ThreadLocal<>();

    private final UUID runId;
    private final String caller;
    private final int maxToolCalls;
    private final AtomicInteger calls = new AtomicInteger();

    private ToolRun(UUID runId, String caller, int maxToolCalls) {
        this.runId = runId;
        this.caller = caller;
        this.maxToolCalls = maxToolCalls;
    }

    public static ToolRun open(UUID runId, String caller, int maxToolCalls) {
        ToolRun run = new ToolRun(runId, caller, maxToolCalls);
        CURRENT.set(run);
        return run;
    }

    public static ToolRun current() {
        ToolRun run = CURRENT.get();
        if (run == null) {
            throw new IllegalStateException("a tool was called outside a triage run");
        }
        return run;
    }

    public static void close() {
        CURRENT.remove();
    }

    public UUID runId() {
        return runId;
    }

    public String caller() {
        return caller;
    }

    /** Counts this call; false once the run's tool-call budget is spent. */
    boolean admit() {
        return calls.incrementAndGet() <= maxToolCalls;
    }

    public int calls() {
        return calls.get();
    }
}
