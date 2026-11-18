package com.explore.opsagent.runs;

import com.explore.opsagent.security.Caller;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of triage runs for the OPS_RUN_VIEWER role (ADR-0002). Every query carries the caller's tenant from
 * the validated token; a run of another tenant is a 404, the same answer as a run that does not exist. The payload,
 * lease and heartbeat columns are never selected.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    public record RunView(UUID id, String alertName, String fingerprint, String owner, String status, String stopReason,
                          int steps, int inputTokens, int outputTokens, int toolCalls, boolean mailed,
                          boolean outcomeUnknown, Instant createdAt, Instant finishedAt) {
    }

    public record ToolCallView(String tool, String outcome, int resultChars, String argumentShape, Instant createdAt) {
    }

    public record RunDetail(RunView run, List<ToolCallView> toolCalls) {
    }

    private static final String COLUMNS = """
            id, alert_name, fingerprint, owner, status, stop_reason, steps, input_tokens, output_tokens, tool_calls,
            mailed, outcome_unknown, created_at, finished_at""";

    private final JdbcTemplate jdbc;

    public RunController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(Authentication authentication,
                                    @RequestParam(defaultValue = "20") int limit) {
        String tenant = Caller.of(authentication).tenant();
        int bounded = Math.max(1, Math.min(limit, 100));
        List<RunView> runs = jdbc.query("SELECT " + COLUMNS
                + " FROM triage_run WHERE tenant_id = ? ORDER BY created_at DESC, id LIMIT ?",
                (rs, i) -> view(rs), tenant, bounded);
        return Map.of("runs", runs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> one(Authentication authentication, @PathVariable UUID id) {
        String tenant = Caller.of(authentication).tenant();
        Optional<RunView> run = jdbc.query("SELECT " + COLUMNS + " FROM triage_run WHERE id = ? AND tenant_id = ?",
                (rs, i) -> view(rs), id, tenant).stream().findFirst();
        if (run.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND"));
        }
        // Reached only through a run already proven to belong to the tenant.
        List<ToolCallView> calls = jdbc.query("""
                SELECT tool, outcome, result_chars, argument_shape::text AS shape, created_at
                  FROM tool_audit WHERE run_id = ? ORDER BY created_at, id""",
                (rs, i) -> new ToolCallView(rs.getString("tool"), rs.getString("outcome"), rs.getInt("result_chars"),
                        rs.getString("shape"), rs.getTimestamp("created_at").toInstant()), id);
        return ResponseEntity.ok(new RunDetail(run.get(), calls));
    }

    private static RunView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp finished = rs.getTimestamp("finished_at");
        return new RunView(rs.getObject("id", UUID.class), rs.getString("alert_name"), rs.getString("fingerprint"),
                rs.getString("owner"), rs.getString("status"), rs.getString("stop_reason"), rs.getInt("steps"),
                rs.getInt("input_tokens"), rs.getInt("output_tokens"), rs.getInt("tool_calls"), rs.getBoolean("mailed"),
                rs.getBoolean("outcome_unknown"), rs.getTimestamp("created_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
