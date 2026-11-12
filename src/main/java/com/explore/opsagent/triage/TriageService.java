package com.explore.opsagent.triage;

import com.explore.opsagent.agent.GuardedChatModel;
import com.explore.opsagent.agent.TriageAssistant;
import com.explore.opsagent.agent.TriageReport;
import com.explore.opsagent.alert.AlertmanagerWebhook;
import com.explore.opsagent.config.OpsAgentProperties;
import com.explore.opsagent.tools.OpsTools;
import com.explore.opsagent.tools.ToolRun;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns firing alerts into triage runs: one run per incident (fingerprint and start time), on a small bounded pool,
 * each run bounded by steps, tokens, tool calls and time, each ending in exactly one email.
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
    /**
     * The agent loop itself runs here, not on {@code pool}: a run waits on its loop with a timeout, and a waiting run
     * must not hold the thread its loop needs. One virtual thread per loop; the pool bounds how many runs exist.
     */
    private final ExecutorService agentThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public TriageService(JdbcTemplate jdbc, ChatModel model, OpsTools tools, TriageMailer mailer, ExecutorService pool,
                         OpsAgentProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.model = model;
        this.tools = tools;
        this.mailer = mailer;
        this.pool = pool;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Intake> accept(AlertmanagerWebhook body) {
        List<Intake> out = new ArrayList<>();
        String caller = properties.webhook().caller();
        for (AlertmanagerWebhook.Alert alert : body.alerts()) {
            if (!alert.firing()) {
                out.add(Intake.RESOLVED_IGNORED);
                continue;
            }
            UUID runId = UUID.randomUUID();
            try {
                jdbc.update("""
                        INSERT INTO triage_run (id, alert_name, fingerprint, starts_at, owner, caller, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                        """, runId, alert.name(), alert.fingerprint(), Timestamp.from(alert.startsAt()),
                        mailer.recipient(alert), caller, Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException repeat) {
                out.add(Intake.DUPLICATE);
                continue;
            }
            try {
                pool.submit(() -> run(runId, alert, caller));
                out.add(Intake.STARTED);
            } catch (RejectedExecutionException full) {
                jdbc.update("UPDATE triage_run SET status = 'FAILED', stop_reason = 'BUSY', finished_at = ? WHERE id = ?",
                        Timestamp.from(clock.instant()), runId);
                out.add(Intake.BUSY);
            }
        }
        return out;
    }

    void run(UUID runId, AlertmanagerWebhook.Alert alert, String caller) {
        OpsAgentProperties.Guardrails g = properties.guardrails();
        Instant deadline = clock.instant().plus(g.timeout());
        GuardedChatModel guarded = new GuardedChatModel(model, g.maxSteps(), g.maxTokens(), deadline, clock);
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
            GuardedChatModel.LimitReached limit = limitIn(e);
            if (limit != null) {
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
        boolean mailed = false;
        try {
            mailer.send(alert, report, runbook, facts);
            mailed = true;
        } catch (RuntimeException mailFailure) {
            LOG.warn("triage run {} could not send its email ({})", runId, mailFailure.getClass().getSimpleName());
        }
        jdbc.update("""
                UPDATE triage_run SET status = ?, stop_reason = ?, steps = ?, input_tokens = ?, output_tokens = ?,
                       tool_calls = ?, mailed = ?, finished_at = ? WHERE id = ?
                """, status, stopReason, guarded.steps(), guarded.inputTokens(), guarded.outputTokens(), toolCalls,
                mailed, Timestamp.from(clock.instant()), runId);
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

    private static GuardedChatModel.LimitReached limitIn(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof GuardedChatModel.LimitReached l) {
                return l;
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
