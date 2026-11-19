// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.triage;

import com.explore.opsagent.agent.GuardedChatModel;
import com.explore.opsagent.agent.TriageAssistant;
import com.explore.opsagent.agent.TriageReport;
import com.explore.opsagent.alert.AlertmanagerWebhook;
import com.explore.opsagent.config.OpsAgentProperties;
import com.explore.opsagent.gateway.GatewayCallException;
import com.explore.opsagent.tools.OpsTools;
import com.explore.opsagent.tools.ToolRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns firing alerts into triage runs: one run per incident (fingerprint and start time), on a small bounded pool,
 * each run bounded by steps, tokens, tool calls and time, each normally ending in one email (none if the send fails).
 * <p>
 * Every run is a durable row (ADR-0001): {@code RECEIVED} at intake, {@code RUNNING} once a worker holds it, then
 * terminal. The row names the instance holding it ({@code lease_owner}) and that instance renews {@code heartbeat_at}
 * for every run it has in memory ({@link #heartbeat()}). Before a model call or the email, the run records that side
 * effect first ({@code side_effect}), and only while it still holds the lease: a run that a sweeper has taken from a
 * stalled instance makes no further call. {@link RunRecovery} handles runs whose holder died.
 * <p>
 * Logs carry ids, counts and outcomes. Never the alert text, a tool result or the model's answer.
 */
public class TriageService {

    private static final Logger LOG = LoggerFactory.getLogger(TriageService.class);

    /** What intake did with each alert. */
    public enum Intake { STARTED, DUPLICATE, RESOLVED_IGNORED, BUSY }

    private final JdbcTemplate jdbc;
    private final ChatModel model;
    private final OpsTools tools;
    private final TriageMailer mailer;
    private final ExecutorService pool;
    private final OpsAgentProperties properties;
    private final Clock clock;
    private final ObjectMapper json;
    private final String instanceId;
    /** Runs this instance holds in memory (queued or running): the ones whose heartbeat it renews. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /**
     * The agent loop itself runs here, not on {@code pool}: a run waits on its loop with a timeout, and a waiting run
     * must not hold the thread its loop needs. One virtual thread per loop; the pool bounds how many runs exist.
     */
    private final ExecutorService agentThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public TriageService(JdbcTemplate jdbc, ChatModel model, OpsTools tools, TriageMailer mailer, ExecutorService pool,
                         OpsAgentProperties properties, Clock clock, ObjectMapper json, String instanceId) {
        this.jdbc = jdbc;
        this.model = model;
        this.tools = tools;
        this.mailer = mailer;
        this.pool = pool;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
        this.instanceId = instanceId;
    }

    /** Thrown inside a run that no longer holds its lease: it stops without a model call, an email or a final write. */
    static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super("lease lost", null, false, false);
        }
    }

    public String instanceId() {
        return instanceId;
    }

    public List<Intake> accept(AlertmanagerWebhook body, String tenant) {
        List<Intake> out = new ArrayList<>();
        String caller = properties.webhook().caller();
        for (AlertmanagerWebhook.Alert alert : body.alerts()) {
            if (!alert.firing()) {
                out.add(Intake.RESOLVED_IGNORED);
                continue;
            }
            UUID runId = UUID.randomUUID();
            Timestamp now = Timestamp.from(clock.instant());
            try {
                // The dedupe key is (tenant, fingerprint, starts_at): a repeat of the incident for the same tenant, in any
                // state, is not a new run. Another tenant's identical alert is its own run (ADR-0002).
                jdbc.update("""
                        INSERT INTO triage_run (id, tenant_id, alert_name, fingerprint, starts_at, owner, caller, status,
                                                created_at, lease_owner, heartbeat_at, alert_payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?, ?::jsonb)
                        """, runId, tenant, alert.name(), alert.fingerprint(), Timestamp.from(alert.startsAt()),
                        mailer.recipient(alert), caller, now, instanceId, now, payload(alert));
            } catch (DuplicateKeyException repeat) {
                out.add(Intake.DUPLICATE);
                continue;
            }
            if (submit(runId, alert, caller)) {
                out.add(Intake.STARTED);
            } else {
                jdbc.update("""
                        UPDATE triage_run SET status = 'FAILED', stop_reason = 'BUSY', finished_at = ?, alert_payload = NULL
                         WHERE id = ? AND lease_owner = ? AND status = 'RECEIVED'
                        """, Timestamp.from(clock.instant()), runId, instanceId);
                out.add(Intake.BUSY);
            }
        }
        return out;
    }

    /**
     * Queues a run this instance holds (status {@code RECEIVED}, {@code lease_owner} = this instance). False when the
     * pool is full; the caller decides what that means.
     */
    boolean submit(UUID runId, AlertmanagerWebhook.Alert alert, String caller) {
        inFlight.add(runId);
        try {
            pool.submit(() -> {
                try {
                    run(runId, alert, caller);
                } catch (RuntimeException e) {
                    LOG.warn("triage run {} ended without its final write ({})", runId, e.getClass().getSimpleName());
                } finally {
                    inFlight.remove(runId);
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            inFlight.remove(runId);
            return false;
        }
    }

    /** Renews the heartbeat of every run this instance holds in memory. One statement, whatever the count. */
    public int heartbeat() {
        if (inFlight.isEmpty()) {
            return 0;
        }
        UUID[] ids = inFlight.toArray(UUID[]::new);
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE triage_run SET heartbeat_at = ?
                     WHERE id = ANY (?) AND lease_owner = ? AND status IN ('RECEIVED', 'RUNNING')
                    """);
            ps.setTimestamp(1, now);
            ps.setArray(2, con.createArrayOf("uuid", ids));
            ps.setString(3, instanceId);
            return ps;
        });
    }

    /** Records that the run is about to make an external call, only while it holds the lease. */
    private void sideEffect(UUID runId, String effect) {
        int held = jdbc.update("""
                UPDATE triage_run SET side_effect = CASE WHEN ? = 'MAIL' THEN 'MAIL'
                                                         WHEN side_effect = 'NONE' THEN 'MODEL'
                                                         ELSE side_effect END,
                                      heartbeat_at = ?
                 WHERE id = ? AND lease_owner = ? AND status = 'RUNNING'
                """, effect, Timestamp.from(clock.instant()), runId, instanceId);
        if (held == 0) {
            throw new LeaseLost();
        }
    }

    void run(UUID runId, AlertmanagerWebhook.Alert alert, String caller) {
        int claimed = jdbc.update("""
                UPDATE triage_run SET status = 'RUNNING', heartbeat_at = ?
                 WHERE id = ? AND lease_owner = ? AND status = 'RECEIVED'
                """, Timestamp.from(clock.instant()), runId, instanceId);
        if (claimed == 0) {
            LOG.info("triage run {} is no longer held by this instance; not started", runId);
            return;
        }
        OpsAgentProperties.Guardrails g = properties.guardrails();
        Instant deadline = clock.instant().plus(g.timeout());
        GuardedChatModel guarded = new GuardedChatModel(model, g.maxSteps(), g.maxTokens(), deadline, clock,
                () -> sideEffect(runId, "MODEL"));
        TriageReport report = null;
        String status = "DONE";
        String stopReason = null;
        int toolCalls = 0;
        Future<TriageReport> future = null;
        try {
            future = agentThreads.submit(() -> {
                ToolRun run = ToolRun.open(runId, caller, g.maxToolCalls());
                try {
                    TriageAssistant assistant = AiServices.builder(TriageAssistant.class)
                            .chatModel(guarded)
                            .tools(tools)
                            .maxSequentialToolsInvocations(g.maxSteps() + 1) // our step guard decides, not this backstop
                            .build();
                    return assistant.triage(describe(alert));
                } finally {
                    ToolRun.close();
                }
            });
            report = future.get(g.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            future.cancel(true);
            status = "STOPPED";
            stopReason = "DEADLINE";
        } catch (Exception e) {
            if (causedBy(e, LeaseLost.class)) {
                LOG.warn("triage run {} lost its lease mid-run; another instance has it", runId);
                return;
            }
            GuardedChatModel.LimitReached limit = limitIn(e);
            GatewayCallException gateway = gatewayIn(e);
            if (gateway != null) {
                // The gateway's per-run budget is a guardrail like ours: STOPPED. Anything else it refused or could not
                // answer is FAILED; the call is not retried (ADR-0001, ADR-0003).
                status = gateway.kind() == GatewayCallException.Kind.RUN_LIMIT ? "STOPPED" : "FAILED";
                stopReason = gateway.stopReason();
            } else if (limit != null) {
                status = "STOPPED";
                stopReason = limit.limit().name();
            } else {
                status = "FAILED";
                stopReason = rootName(e);
            }
        }
        toolCalls = jdbc.queryForObject("SELECT count(*) FROM tool_audit WHERE run_id = ?", Integer.class, runId);
        String runbook;
        try {
            runbook = tools.runbookSection(alert.name());
        } catch (Exception e) {
            runbook = "runbook unavailable (" + e.getClass().getSimpleName() + ")";
        }
        TriageMailer.RunFacts facts = new TriageMailer.RunFacts(runId.toString(), status, stopReason, guarded.steps(),
                guarded.inputTokens(), guarded.outputTokens(), toolCalls);
        try {
            sideEffect(runId, "MAIL");
        } catch (LeaseLost lost) {
            LOG.warn("triage run {} lost its lease before its email; not sent", runId);
            return;
        }
        boolean mailed = false;
        try {
            mailer.send(alert, report, runbook, facts);
            mailed = true;
        } catch (RuntimeException mailFailure) {
            LOG.warn("triage run {} could not send its email ({})", runId, mailFailure.getClass().getSimpleName());
        }
        jdbc.update("""
                UPDATE triage_run SET status = ?, stop_reason = ?, steps = ?, input_tokens = ?, output_tokens = ?,
                       tool_calls = ?, mailed = ?, finished_at = ?, alert_payload = NULL
                 WHERE id = ? AND lease_owner = ? AND status = 'RUNNING'
                """, status, stopReason, guarded.steps(), guarded.inputTokens(), guarded.outputTokens(), toolCalls,
                mailed, Timestamp.from(clock.instant()), runId, instanceId);
        LOG.info("triage run {} alert {} {} steps={} toolCalls={} tokens={}/{} mailed={}", runId, alert.name(), status,
                guarded.steps(), toolCalls, guarded.inputTokens(), guarded.outputTokens(), mailed);
    }

    /** The alert as the model sees it: name, labels, the summary annotation, the start. Bounded by AlertValidation. */
    static String describe(AlertmanagerWebhook.Alert alert) {
        StringBuilder b = new StringBuilder(alert.name()).append(" firing since ").append(alert.startsAt()).append('\n');
        for (Map.Entry<String, String> l : new TreeMap<>(alert.labels()).entrySet()) {
            b.append(l.getKey()).append('=').append(l.getValue()).append('\n');
        }
        if (alert.annotations() != null && alert.annotations().get("summary") != null) {
            b.append("summary: ").append(alert.annotations().get("summary")).append('\n');
        }
        return b.toString();
    }

    String payload(AlertmanagerWebhook.Alert alert) {
        try {
            return json.writeValueAsString(alert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("alert not serialisable", e);
        }
    }

    AlertmanagerWebhook.Alert alertFrom(String payload) {
        try {
            return json.readValue(payload, AlertmanagerWebhook.Alert.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored alert not readable", e);
        }
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static GuardedChatModel.LimitReached limitIn(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof GuardedChatModel.LimitReached l) {
                return l;
            }
        }
        return null;
    }

    private static GatewayCallException gatewayIn(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof GatewayCallException g) {
                return g;
            }
        }
        return null;
    }

    private static String rootName(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String name = t.getClass().getSimpleName();
        return name.length() > 32 ? name.substring(0, 32) : name;
    }
}
