package com.explore.opsagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One real triage run against OpenAI (plan 12.5, live half). Tagged {@code openai}: excluded from the default build and
 * from CI. Run it only on purpose, with OPENAI_API_KEY in the environment:
 * {@code ./mvnw -B -ntp verify -Dit.excludedGroups= -Dit.test=LiveTriageIT}. The same fixtures as GuardrailIT; the
 * guardrails are the shipped ones, so one run is bounded by the configured step, token and tool-call limits.
 */
@Tag("openai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Live triage run against OpenAI (plan 12.5, opt-in)")
class LiveTriageIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String fixtures = "http://127.0.0.1:" + GuardrailIT.FIXTURES.getAddress().getPort();
        r.add("spring.datasource.url", GuardrailIT.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", GuardrailIT.POSTGRES::getUsername);
        r.add("spring.datasource.password", GuardrailIT.POSTGRES::getPassword);
        r.add("spring.mail.host", GuardrailIT.MAILPIT::getHost);
        r.add("spring.mail.port", () -> GuardrailIT.MAILPIT.getMappedPort(1025));
        r.add("opsagent.webhook.token", () -> GuardrailIT.TOKEN);
        r.add("opsagent.model.mode", () -> "direct"); // paid, direct to the provider: opt-in only (ADR-0003)
        r.add("opsagent.model.api-key", () -> System.getenv("OPENAI_API_KEY"));
        r.add("opsagent.sources.prometheus-url", () -> fixtures);
        r.add("opsagent.sources.health.gateway", () -> fixtures + "/actuator/health/readiness");
        r.add("opsagent.sources.log-dir", () -> "src/test/resources/fixtures/logs");
        r.add("opsagent.sources.runbook", () -> "src/test/resources/fixtures/RUNBOOK.md");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-12T05:00:30Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @LocalServerPort
    int port;

    @Test
    @DisplayName("a real model triages the fixture alert with the read-only tools and the email goes out")
    void liveRun() throws Exception {
        String fp = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String body = Files.readString(Path.of("src/test/resources/fixtures/alerts/provider-error-ratio.json"))
                .replace("5a7c2b1d9e0f3a41", fp);
        HttpResponse<String> accepted = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/alerts"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + GuardrailIT.TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode());

        Map<String, Object> run = null;
        for (int i = 0; i < 1200 && run == null; i++) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM triage_run WHERE fingerprint = ? AND status <> 'RUNNING'", fp);
            run = rows.isEmpty() ? null : rows.get(0);
            if (run == null) {
                Thread.sleep(100);
            }
        }
        assertTrue(run != null, "run did not finish");
        List<Map<String, Object>> tools = jdbc.queryForList(
                "SELECT tool, outcome FROM tool_audit WHERE run_id = ? ORDER BY created_at, id", run.get("id"));
        // Measured figures for the evidence file; no prompt or answer text is printed.
        System.out.printf("LIVE status=%s stop=%s steps=%s toolCalls=%s inputTokens=%s outputTokens=%s mailed=%s tools=%s%n",
                run.get("status"), run.get("stop_reason"), run.get("steps"), run.get("tool_calls"),
                run.get("input_tokens"), run.get("output_tokens"), run.get("mailed"), tools);
        assertEquals("DONE", run.get("status"), String.valueOf(run.get("stop_reason")));
        assertEquals(Boolean.TRUE, run.get("mailed"));
        assertTrue(((Number) run.get("tool_calls")).intValue() >= 1, "the model used at least one tool");
    }
}
