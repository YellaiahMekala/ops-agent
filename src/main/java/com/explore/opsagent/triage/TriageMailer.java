package com.explore.opsagent.triage;

import com.explore.opsagent.agent.TriageReport;
import com.explore.opsagent.alert.AlertmanagerWebhook;
import com.explore.opsagent.config.OpsAgentProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The email (plan 12.4): what fired, the agent's findings and likely cause, the runbook's steps, links to the
 * evidence, and how the run went. The runbook section is quoted from the file, not from the model, so the steps an
 * owner follows are the steps the owner wrote. A run stopped by a guardrail still sends: the deterministic parts are
 * worth having, and silence would look like "no problem".
 */
public class TriageMailer {

    /** Run figures for the footer. */
    public record RunFacts(String runId, String status, String stopReason, int steps, int inputTokens, int outputTokens,
                           int toolCalls) {
    }

    private static final Pattern OWNER = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final JavaMailSender mail;
    private final OpsAgentProperties.Mail settings;
    private final String dashboardUrl;

    public TriageMailer(JavaMailSender mail, OpsAgentProperties.Mail settings, String dashboardUrl) {
        this.mail = mail;
        this.settings = settings;
        this.dashboardUrl = dashboardUrl;
    }

    /** The owner label names a team; anything that is not a plain team name goes to the fallback owner. */
    public String recipient(AlertmanagerWebhook.Alert alert) {
        String owner = alert.owner();
        if (owner == null || !OWNER.matcher(owner).matches()) {
            owner = settings.fallbackOwner();
        }
        return owner + "@" + settings.ownerDomain();
    }

    public void send(AlertmanagerWebhook.Alert alert, TriageReport report, String runbook, RunFacts run) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(settings.from());
        m.setTo(recipient(alert));
        m.setSubject("[TRIAGE] " + alert.name() + " (" + alert.labels().getOrDefault("severity", "unknown")
                + ") owner=" + alert.labels().getOrDefault("owner", "unknown"));
        m.setText(body(alert, report, runbook, run, dashboardUrl));
        mail.send(m);
    }

    /**
     * The operator notice for a run that recovery closed instead of resubmitting (ADR-0001): its holder died, and
     * either a model call or the triage email may already have happened, or it ran out of attempts. Says what is
     * known and what is not; repeats nothing.
     */
    public void sendRecoveryNotice(AlertmanagerWebhook.Alert alert, String runId, String reason, String sideEffect,
                                   int attempts) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(settings.from());
        m.setTo(recipient(alert));
        m.setSubject("[TRIAGE " + ("OUTCOME_UNKNOWN".equals(reason) ? "OUTCOME UNKNOWN" : "NOT COMPLETED") + "] "
                + alert.name() + " owner=" + alert.labels().getOrDefault("owner", "unknown"));
        m.setText(noticeBody(alert, runId, reason, sideEffect, attempts));
        mail.send(m);
    }

    static String noticeBody(AlertmanagerWebhook.Alert alert, String runId, String reason, String sideEffect,
                             int attempts) {
        StringBuilder b = new StringBuilder();
        b.append("WHAT FIRED\n  ").append(alert.name()).append(" since ").append(alert.startsAt()).append('\n');
        b.append("\nWHAT HAPPENED\n");
        b.append("  The ops-agent instance running triage for this alert stopped before the run finished.\n");
        if ("OUTCOME_UNKNOWN".equals(reason)) {
            b.append("  It had begun ").append("MAIL".equals(sideEffect) ? "sending the triage email" : "calling the model")
                    .append(", so whether that completed is unknown.\n");
            b.append("  The run was not repeated, to avoid a second model call or a second email.\n");
            b.append("  A triage email for this alert may or may not have arrived. Triage it by hand if not.\n");
        } else {
            b.append("  It was retried ").append(attempts - 1).append(" time(s) and did not finish; it is not retried again.\n");
            b.append("  No model call or email was made by it. Triage this alert by hand.\n");
        }
        b.append("\nRUN\n  id ").append(runId).append(", FAILED (").append(reason).append("), attempts ").append(attempts)
                .append('\n');
        b.append("  The agent has read-only tools. It changed nothing.\n");
        return b.toString();
    }

    static String body(AlertmanagerWebhook.Alert alert, TriageReport report, String runbook, RunFacts run,
                       String dashboardUrl) {
        StringBuilder b = new StringBuilder();
        b.append("WHAT FIRED\n");
        b.append("  ").append(alert.name()).append(" since ").append(alert.startsAt()).append('\n');
        for (Map.Entry<String, String> l : new TreeMap<>(alert.labels()).entrySet()) {
            b.append("  ").append(l.getKey()).append('=').append(l.getValue()).append('\n');
        }
        if (alert.annotations() != null && alert.annotations().get("summary") != null) {
            b.append("  summary: ").append(alert.annotations().get("summary")).append('\n');
        }

        b.append("\nFINDINGS (the agent's reading of read-only data; check before acting)\n");
        if (report == null || report.findings() == null || report.findings().isEmpty()) {
            b.append("  none: the agent stopped before reporting (").append(run.stopReason()).append(")\n");
        } else {
            for (String f : report.findings()) {
                b.append("  - ").append(f).append('\n');
            }
        }
        b.append("\nLIKELY CAUSE\n  ")
                .append(report == null || report.likelyCause() == null ? "unknown" : report.likelyCause())
                .append("\n  confidence: ").append(report == null || report.confidence() == null ? "n/a" : report.confidence())
                .append('\n');

        b.append("\nRUNBOOK (quoted from the runbook, not written by the agent)\n");
        runbook.lines().forEach(line -> b.append("  ").append(line).append('\n'));

        b.append("\nEVIDENCE\n");
        if (alert.generatorURL() != null) {
            b.append("  expression: ").append(alert.generatorURL()).append('\n');
        }
        b.append("  dashboard: ").append(dashboardUrl).append('\n');
        if (alert.annotations() != null && alert.annotations().get("runbook") != null) {
            b.append("  runbook: ").append(alert.annotations().get("runbook")).append('\n');
        }

        b.append("\nRUN\n  id ").append(run.runId()).append(", ").append(run.status());
        if (run.stopReason() != null) {
            b.append(" (").append(run.stopReason()).append(')');
        }
        b.append(", model steps ").append(run.steps()).append(", tool calls ").append(run.toolCalls())
                .append(", tokens in/out ").append(run.inputTokens()).append('/').append(run.outputTokens()).append('\n');
        b.append("  The agent has read-only tools. It changed nothing.\n");
        return b.toString();
    }
}
