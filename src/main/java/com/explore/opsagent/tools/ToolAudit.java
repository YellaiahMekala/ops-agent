// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One row per tool call (plan 12.3): which tool, the <em>shape</em> of its arguments, the size of its result, the
 * outcome and the caller. Never an argument value or a result: a PromQL string or a log line can carry whatever the
 * system it came from put in it, and this table is kept longer than any of that.
 * <p>
 * Shape means: for each argument, its name, its type and its length. Enough to see that the agent asked for a 400
 * character query or a 60-minute log window; not enough to reconstruct what it asked.
 */
public class ToolAudit {

    public enum Outcome { OK, DENIED, LIMIT, ERROR }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ToolAudit(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(ToolRun run, String tool, Map<String, Object> arguments, int resultChars, Outcome outcome) {
        Map<String, Object> shape = new LinkedHashMap<>();
        arguments.forEach((name, value) -> shape.put(name, value == null ? "null"
                : value instanceof String s ? "string(" + s.length() + ")"
                : value.getClass().getSimpleName().toLowerCase()));
        String shapeJson;
        try {
            shapeJson = JSON.writeValueAsString(shape);
        } catch (JsonProcessingException e) {
            shapeJson = "{}";
        }
        jdbc.update("""
                INSERT INTO tool_audit (id, run_id, tool, argument_shape, result_chars, outcome, caller, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, UUID.randomUUID(), run.runId(), tool, shapeJson, resultChars, outcome.name(), run.caller(),
                Timestamp.from(clock.instant()));
    }
}
