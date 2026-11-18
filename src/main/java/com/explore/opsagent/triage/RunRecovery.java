package com.explore.opsagent.triage;

import com.explore.opsagent.alert.AlertmanagerWebhook;
import com.explore.opsagent.config.OpsAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Recovers triage runs whose instance died (ADR-0001). Once at startup and then every {@code sweep-every}, it claims
 * runs that are not terminal and whose heartbeat is older than {@code stale-after}, and for each:
 * <ul>
 *   <li>no external call begun ({@code side_effect = NONE}): resubmitted on this instance, at most
 *       {@code max-attempts} runs in total, then closed {@code FAILED/RECOVERY_LIMIT} with a notice;</li>
 *   <li>a model call or the email may have happened ({@code MODEL}, {@code MAIL}): not repeated. Closed
 *       {@code FAILED/OUTCOME_UNKNOWN} with {@code outcome_unknown = true}, and the owner gets a notice saying so.</li>
 * </ul>
 * The claim is one {@code UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING}: two instances
 * sweeping at once never claim the same row, and a row claimed and committed by one is no longer stale for the other.
 * Logs carry run ids and counts only.
 */
public class RunRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(RunRecovery.class);

    /** What one sweep did. */
    public record Sweep(int resubmitted, int deferred, int outcomeUnknown, int attemptsExhausted) {
        public int claimed() {
            return resubmitted + deferred + outcomeUnknown + attemptsExhausted;
        }
    }

    private record Claimed(UUID id, String sideEffect, int attempts, String payload, String caller) {
    }

    private final JdbcTemplate jdbc;
    private final TriageService triage;
    private final TriageMailer mailer;
    private final OpsAgentProperties.Recovery settings;
    private final Clock clock;
    private ScheduledExecutorService timer;

    public RunRecovery(JdbcTemplate jdbc, TriageService triage, TriageMailer mailer,
                       OpsAgentProperties.Recovery settings, Clock clock) {
        this.jdbc = jdbc;
        this.triage = triage;
        this.mailer = mailer;
        this.settings = settings;
        this.clock = clock;
    }

    /** Startup sweep, then the heartbeat and the periodic sweep. After startup, so the pool and mail are ready. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        guarded("startup sweep", this::sweep);
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("recovery").daemon().factory());
        long beat = settings.heartbeatEvery().toMillis();
        long sweep = settings.sweepEvery().toMillis();
        timer.scheduleWithFixedDelay(() -> guarded("heartbeat", triage::heartbeat), beat, beat, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(() -> guarded("sweep", this::sweep), sweep, sweep, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    /** One pass. Safe to run on any number of instances at once. */
    public Sweep sweep() {
        Instant now = clock.instant();
        Timestamp cutoff = Timestamp.from(now.minus(settings.staleAfter()));
        String me = triage.instanceId();
        List<Claimed> claimed = jdbc.query("""
                UPDATE triage_run t
                   SET lease_owner = ?, heartbeat_at = ?,
                       attempts = CASE WHEN t.side_effect = 'NONE' THEN t.attempts + 1 ELSE t.attempts END
                 WHERE t.id IN (SELECT id FROM triage_run
                                 WHERE status IN ('RECEIVED', 'RUNNING') AND heartbeat_at < ?
                                 ORDER BY heartbeat_at
                                 LIMIT ?
                                   FOR UPDATE SKIP LOCKED)
                   AND t.status IN ('RECEIVED', 'RUNNING') AND t.heartbeat_at < ?
                RETURNING t.id, t.side_effect, t.attempts, t.alert_payload::text AS payload, t.caller
                """, (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("side_effect"),
                        rs.getInt("attempts"), rs.getString("payload"), rs.getString("caller")),
                me, Timestamp.from(now), cutoff, settings.sweepBatch(), cutoff);

        int resubmitted = 0;
        int deferred = 0;
        int unknown = 0;
        int exhausted = 0;
        for (Claimed run : claimed) {
            if (!"NONE".equals(run.sideEffect())) {
                close(run, "OUTCOME_UNKNOWN", true);
                unknown++;
            } else if (run.attempts() > settings.maxAttempts() || run.payload() == null) {
                close(run, "RECOVERY_LIMIT", false);
                exhausted++;
            } else if (resubmit(run)) {
                resubmitted++;
            } else {
                deferred++;
            }
        }
        Sweep result = new Sweep(resubmitted, deferred, unknown, exhausted);
        if (result.claimed() > 0) {
            LOG.warn("recovery sweep on {}: resubmitted={} deferred={} outcomeUnknown={} attemptsExhausted={}", me,
                    resubmitted, deferred, unknown, exhausted);
        }
        return result;
    }

    private boolean resubmit(Claimed run) {
        int held = jdbc.update("""
                UPDATE triage_run SET status = 'RECEIVED'
                 WHERE id = ? AND lease_owner = ? AND status IN ('RECEIVED', 'RUNNING')
                """, run.id(), triage.instanceId());
        if (held == 0) {
            return false;
        }
        AlertmanagerWebhook.Alert alert = triage.alertFrom(run.payload());
        if (triage.submit(run.id(), alert, run.caller())) {
            LOG.info("triage run {} resubmitted, attempt {}", run.id(), run.attempts());
            return true;
        }
        // Pool full: the row stays RECEIVED under this instance with no heartbeat, so a later sweep takes it again.
        LOG.info("triage run {} not resubmitted now (pool full); a later sweep retries it", run.id());
        return false;
    }

    private void close(Claimed run, String reason, boolean outcomeUnknown) {
        int closed = jdbc.update("""
                UPDATE triage_run SET status = 'FAILED', stop_reason = ?, outcome_unknown = ?, finished_at = ?,
                                      alert_payload = NULL
                 WHERE id = ? AND lease_owner = ? AND status IN ('RECEIVED', 'RUNNING')
                """, reason, outcomeUnknown, Timestamp.from(clock.instant()), run.id(), triage.instanceId());
        if (closed == 0) {
            return;
        }
        LOG.warn("triage run {} closed by recovery: {} (side effect {})", run.id(), reason, run.sideEffect());
        if (run.payload() == null) {
            return; // nothing to address a notice with; the row says what happened
        }
        try {
            mailer.sendRecoveryNotice(triage.alertFrom(run.payload()), run.id().toString(), reason, run.sideEffect(),
                    run.attempts());
            jdbc.update("UPDATE triage_run SET notice_sent = TRUE WHERE id = ?", run.id());
        } catch (RuntimeException mailFailure) {
            LOG.warn("triage run {}: recovery notice not sent ({})", run.id(), mailFailure.getClass().getSimpleName());
        }
    }

    private static void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.warn("recovery {} failed ({}); next tick retries", what, e.getClass().getSimpleName());
        }
    }
}
