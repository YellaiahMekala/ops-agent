// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent;

import com.explore.opsagent.config.OpsAgentProperties;
import com.explore.opsagent.tools.OpsTools;
import com.explore.opsagent.triage.RunRecovery;
import com.explore.opsagent.triage.TriageMailer;
import com.explore.opsagent.triage.TriageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run recovery after restart (ADR-0001), against real Postgres and Mailpit with the scripted model. A dead instance is
 * simulated by a row it left behind: not terminal, held by an instance id that no longer runs, heartbeat older than
 * {@code stale-after}. The test clock is moved by hand; the scheduled heartbeat and sweep are set to an hour, so every
 * sweep here is called explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Run recovery: stale runs resubmitted once or closed as unknown, dedupe, concurrent sweepers (ADR-0001)")
class RunRecoveryIT {

    static final String TOKEN = "test-webhook-token-not-a-secret";
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ME = "instance-a";
    static final String DEAD = "instance-dead";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.27")
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T05:00:30Z"));

    static {
        POSTGRES.start();
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.mail.host", MAILPIT::getHost);
        r.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        r.add("opsagent.webhook.token", () -> TOKEN);
        r.add("opsagent.webhook.queue-capacity", () -> "32");
        r.add("opsagent.model.api-key", () -> "");
        r.add("opsagent.sources.log-dir", () -> "src/test/resources/fixtures/logs");
        r.add("opsagent.sources.runbook", () -> "src/test/resources/fixtures/RUNBOOK.md");
        r.add("opsagent.guardrails.timeout", () -> "10s");
        r.add("opsagent.recovery.instance-id", () -> ME);
        r.add("opsagent.recovery.heartbeat-every", () -> "1h");
        r.add("opsagent.recovery.sweep-every", () -> "1h");
        r.add("opsagent.recovery.stale-after", () -> "60s");
        r.add("opsagent.recovery.max-attempts", () -> "3");
        r.add("logging.structured.format.console", () -> "");
    }

    @TestConfiguration
    static class Doubles {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    ScriptedChatModel model;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TriageService triage;
    @Autowired
    RunRecovery recovery;
    @Autowired
    OpsTools tools;
    @Autowired
    TriageMailer mailer;
    @Autowired
    OpsAgentProperties properties;
    @Autowired
    ObjectMapper appJson;
    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() throws Exception {
        model.reset();
        // Each test owns the live rows it makes: close whatever an earlier test left, so a sweep sees only this test's.
        jdbc.update("UPDATE triage_run SET status = 'FAILED', stop_reason = 'TEST_RESET', alert_payload = NULL "
                + "WHERE status IN ('RECEIVED', 'RUNNING')");
        http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    static URI mailpit(String path) {
        return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
    }

    static String fingerprint() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** The firing fixture as a webhook body, with the given fingerprint. */
    static String webhook(String fp) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/alerts/provider-error-ratio.json"))
                .replace("5a7c2b1d9e0f3a41", fp);
    }

    /**
     * What a dead instance leaves behind: a run row it held, with the alert payload, a heartbeat {@code age} old and
     * the given status and side effect.
     */
    UUID deadRun(String fp, String status, String sideEffect, int attempts, Duration age) throws IOException {
        JsonNode alert = JSON.readTree(webhook(fp)).at("/alerts/0");
        UUID id = UUID.randomUUID();
        Timestamp beat = Timestamp.from(CLOCK.instant().minus(age));
        jdbc.update("""
                INSERT INTO triage_run (id, tenant_id, alert_name, fingerprint, starts_at, owner, caller, status, created_at,
                                        lease_owner, heartbeat_at, attempts, side_effect, alert_payload)
                VALUES (?, 'legacy', ?, ?, ?, 'gateway-oncall@gateway.invalid', 'alertmanager', ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, id, alert.at("/labels/alertname").asText(), fp,
                Timestamp.from(Instant.parse(alert.path("startsAt").asText())), status, beat, DEAD, beat, attempts,
                sideEffect, alert.toString());
        return id;
    }

    Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM triage_run WHERE id = ?", id);
    }

    Map<String, Object> finished(UUID id) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Map<String, Object> r = row(id);
            if (!"RECEIVED".equals(r.get("status")) && !"RUNNING".equals(r.get("status"))) {
                return r;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("run did not finish");
    }

    /** Waits for exactly {@code n} mails and returns their summaries. */
    List<JsonNode> mails(int n) throws Exception {
        JsonNode list = null;
        for (int i = 0; i < 60; i++) {
            list = JSON.readTree(http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            if (list.path("messages").size() >= n) {
                break;
            }
            Thread.sleep(100);
        }
        Thread.sleep(300); // and no more arrive
        list = JSON.readTree(http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        List<JsonNode> out = new ArrayList<>();
        list.path("messages").forEach(out::add);
        assertEquals(n, out.size(), "mail count");
        return out;
    }

    String text(JsonNode summary) throws Exception {
        return JSON.readTree(http.send(HttpRequest.newBuilder(mailpit("/api/v1/message/" + summary.path("ID").asText()))
                .build(), HttpResponse.BodyHandlers.ofString()).body()).path("Text").asText();
    }

    HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/alerts"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("crash mid-run before any model call: the stale run is resubmitted once, runs, and mails once")
    void staleRunIsResubmittedOnce() throws Exception {
        UUID dead = deadRun(fingerprint(), "RUNNING", "NONE", 1, Duration.ofMinutes(5));
        UUID alive = deadRun(fingerprint(), "RUNNING", "NONE", 1, Duration.ofSeconds(5));
        jdbc.update("UPDATE triage_run SET lease_owner = 'instance-live' WHERE id = ?", alive);

        RunRecovery.Sweep first = recovery.sweep();
        RunRecovery.Sweep second = recovery.sweep();
        assertEquals(new RunRecovery.Sweep(1, 0, 0, 0), first);
        assertEquals(0, second.claimed(), "a claimed run has a fresh heartbeat: not stale for the next sweep");

        Map<String, Object> run = finished(dead);
        assertEquals("DONE", run.get("status"));
        assertEquals(2, ((Number) run.get("attempts")).intValue());
        assertEquals(ME, run.get("lease_owner"));
        assertEquals("MAIL", run.get("side_effect"));
        assertEquals(Boolean.TRUE, run.get("mailed"));
        assertEquals(Boolean.FALSE, run.get("outcome_unknown"));
        assertNull(run.get("alert_payload"), "the alert is kept only while the run is live");
        assertEquals(1, model.calls());
        assertTrue(mails(1).get(0).path("Subject").asText().startsWith("[TRIAGE] GatewayProviderErrorRatio"));
        assertEquals(0, recovery.sweep().claimed());

        Map<String, Object> other = row(alive);
        assertEquals("RUNNING", other.get("status"), "a run with a fresh heartbeat is left to its holder");
        assertEquals("instance-live", other.get("lease_owner"));
    }

    @Test
    @DisplayName("unknown outcome: a stale run that had begun a model call or its email is not redone; FAILED, flagged, notice sent")
    void unknownOutcomeIsNotRedone() throws Exception {
        UUID afterModel = deadRun(fingerprint(), "RUNNING", "MODEL", 1, Duration.ofMinutes(5));
        UUID afterMail = deadRun(fingerprint(), "RUNNING", "MAIL", 1, Duration.ofMinutes(5));

        assertEquals(new RunRecovery.Sweep(0, 0, 2, 0), recovery.sweep());

        for (UUID id : List.of(afterModel, afterMail)) {
            Map<String, Object> run = row(id);
            assertEquals("FAILED", run.get("status"));
            assertEquals("OUTCOME_UNKNOWN", run.get("stop_reason"));
            assertEquals(Boolean.TRUE, run.get("outcome_unknown"));
            assertEquals(Boolean.TRUE, run.get("notice_sent"));
            assertEquals(Boolean.FALSE, run.get("mailed"));
            assertEquals(1, ((Number) run.get("attempts")).intValue());
            assertNull(run.get("alert_payload"));
        }
        assertEquals(0, model.calls(), "no model call is repeated");
        List<JsonNode> notices = mails(2);
        for (JsonNode n : notices) {
            assertTrue(n.path("Subject").asText().startsWith("[TRIAGE OUTCOME UNKNOWN] GatewayProviderErrorRatio"));
            String text = text(n);
            assertTrue(text.contains("was not repeated"), text);
            assertTrue(text.contains("FAILED (OUTCOME_UNKNOWN)"));
        }
        assertEquals(0, recovery.sweep().claimed());
    }

    @Test
    @DisplayName("a run that keeps dying before its model call is retried up to max-attempts, then closed with a notice")
    void attemptsAreBounded() throws Exception {
        UUID poison = deadRun(fingerprint(), "RECEIVED", "NONE", 3, Duration.ofMinutes(5));

        assertEquals(new RunRecovery.Sweep(0, 0, 0, 1), recovery.sweep());
        Map<String, Object> run = row(poison);
        assertEquals("FAILED", run.get("status"));
        assertEquals("RECOVERY_LIMIT", run.get("stop_reason"));
        assertEquals(Boolean.FALSE, run.get("outcome_unknown"), "nothing external happened");
        assertEquals(0, model.calls());
        assertTrue(mails(1).get(0).path("Subject").asText().startsWith("[TRIAGE NOT COMPLETED]"));
    }

    @Test
    @DisplayName("dedupe: Alertmanager re-delivering an alert, during a live run, a dead run or concurrently, makes no second run")
    void duplicateDeliveriesMakeOneRun() throws Exception {
        // A dead instance's run for this alert: the redelivery is a DUPLICATE, and recovery runs the one row.
        String fpDead = fingerprint();
        UUID dead = deadRun(fpDead, "RECEIVED", "NONE", 1, Duration.ofMinutes(5));
        assertTrue(post(webhook(fpDead)).body().contains("DUPLICATE"));

        // Five concurrent deliveries of a new alert: one STARTED, four DUPLICATE.
        String fpNew = fingerprint();
        String body = webhook(fpNew);
        ExecutorService senders = Executors.newFixedThreadPool(5);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> replies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            replies.add(senders.submit(() -> {
                go.await();
                return post(body).body();
            }));
        }
        go.countDown();
        int started = 0;
        int duplicate = 0;
        for (Future<String> f : replies) {
            String reply = f.get(20, TimeUnit.SECONDS);
            started += reply.contains("STARTED") ? 1 : 0;
            duplicate += reply.contains("DUPLICATE") ? 1 : 0;
        }
        senders.shutdown();
        assertEquals(1, started);
        assertEquals(4, duplicate);

        assertEquals(1, recovery.sweep().resubmitted());
        assertEquals("DONE", finished(dead).get("status"));
        UUID fresh = jdbc.queryForObject("SELECT id FROM triage_run WHERE fingerprint = ?", UUID.class, fpNew);
        assertEquals("DONE", finished(fresh).get("status"));
        assertTrue(post(webhook(fpDead)).body().contains("DUPLICATE"), "and after it finished");
        for (String fp : List.of(fpDead, fpNew)) {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM triage_run WHERE fingerprint = ?", Integer.class, fp));
        }
        assertEquals(2, model.calls());
        mails(2);
    }

    @Test
    @DisplayName("two instances sweeping at once: every stale run is claimed by exactly one and resubmitted once")
    void concurrentSweepersDoNotDoubleProcess() throws Exception {
        int n = 12;
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            runs.add(deadRun(fingerprint(), i % 2 == 0 ? "RUNNING" : "RECEIVED", "NONE", 1, Duration.ofMinutes(5)));
        }
        // Instance B: the same application beans and database, its own id, pool and recovery, small batches.
        ExecutorService poolB = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
        TriageService triageB = new TriageService(jdbc, model, tools, mailer, poolB, properties, CLOCK, appJson,
                "instance-b");
        OpsAgentProperties.Recovery r = properties.recovery();
        RunRecovery recoveryB = new RunRecovery(jdbc, triageB, mailer, new OpsAgentProperties.Recovery("instance-b",
                r.heartbeatEvery(), r.staleAfter(), r.sweepEvery(), r.maxAttempts(), 2), CLOCK);

        ExecutorService sweepers = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> totals = new ArrayList<>();
        for (RunRecovery sweeper : List.of(recovery, recoveryB)) {
            Callable<Integer> loop = () -> {
                go.await();
                int resubmitted = 0;
                RunRecovery.Sweep s;
                do {
                    s = sweeper.sweep();
                    resubmitted += s.resubmitted();
                } while (s.claimed() > 0);
                return resubmitted;
            };
            totals.add(sweepers.submit(loop));
        }
        go.countDown();
        int total = totals.get(0).get(30, TimeUnit.SECONDS) + totals.get(1).get(30, TimeUnit.SECONDS);
        sweepers.shutdown();
        assertEquals(n, total, "each stale run resubmitted once across both instances");

        Set<Object> owners = new HashSet<>();
        for (UUID id : runs) {
            Map<String, Object> run = finished(id);
            assertEquals("DONE", run.get("status"));
            assertEquals(2, ((Number) run.get("attempts")).intValue(), "claimed once, not twice");
            owners.add(run.get("lease_owner"));
        }
        assertTrue(Set.of(ME, "instance-b").containsAll(owners), owners.toString());
        assertEquals(n, model.calls(), "one model call per run, none doubled");
        mails(n);
        poolB.shutdown();
    }

    @Test
    @DisplayName("a live run's heartbeat keeps sweepers off it, however long it takes")
    void liveRunIsNotStolen() throws Exception {
        model.slow(1500).answer("{\"findings\":[],\"likelyCause\":\"unknown\",\"confidence\":\"LOW\"}");
        String fp = fingerprint();
        assertTrue(post(webhook(fp)).body().contains("STARTED"));
        UUID id = jdbc.queryForObject("SELECT id FROM triage_run WHERE fingerprint = ?", UUID.class, fp);
        // Wait until the (slow) model call is in flight, not just RUNNING: the guard checks the run deadline before
        // each call, so advancing the clock earlier stops the run (seen once on a slow CI runner).
        for (int i = 0; i < 100 && model.calls() < 1; i++) {
            Thread.sleep(50);
        }
        assertEquals("RUNNING", row(id).get("status"));

        CLOCK.advance(Duration.ofMinutes(5));
        assertEquals(1, triage.heartbeat());
        assertEquals(0, recovery.sweep().claimed());

        Map<String, Object> run = finished(id);
        assertEquals("DONE", run.get("status"));
        assertEquals(1, ((Number) run.get("attempts")).intValue());
        assertEquals(1, model.calls());
        mails(1);
    }
}
