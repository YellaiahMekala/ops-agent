// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.tools;

import com.explore.opsagent.config.OpsAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The agent's four tools (plan 12.2). All of them read; none can write, restart, scale, silence or delete anything,
 * because no such method exists here and the only HTTP client they hold is {@link ReadOnlyHttp}.
 * <p>
 * Every call goes through {@link #guarded}: the caller's role must allow the tool ({@link ToolPolicy}), the run's
 * tool-call budget must not be spent, and one {@link ToolAudit} row is written whatever happens. Results are bounded
 * and wrapped as untrusted data: a log line or a label value is input to the model, never an instruction to it.
 */
public class OpsTools {

    public static final List<String> TOOL_NAMES = List.of("queryPrometheus", "searchLogs", "readRunbook", "checkHealth");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ALERT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int MAX_PROMQL = 500;
    private static final int MAX_SERIES = 20;
    private static final int MAX_LOG_LINES = 20;

    private final OpsAgentProperties.Sources sources;
    private final ReadOnlyHttp http;
    private final ToolPolicy policy;
    private final ToolAudit audit;
    private final Clock clock;

    public OpsTools(OpsAgentProperties.Sources sources, ReadOnlyHttp http, ToolPolicy policy, ToolAudit audit,
                    Clock clock) {
        this.sources = sources;
        this.http = http;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    @Tool("Run an instant PromQL query against the gateway's Prometheus and return up to 20 series with their values. "
            + "Read-only. Use it to check rates, ratios and gauges named in the alert.")
    public String queryPrometheus(@P("an instant PromQL expression, at most 500 characters") String promql) {
        return guarded("queryPrometheus", Map.of("promql", nullSafe(promql)), () -> {
            if (promql == null || promql.isBlank() || promql.length() > MAX_PROMQL) {
                return "refused: the query must be 1 to " + MAX_PROMQL + " characters";
            }
            URI uri = URI.create(sources.prometheusUrl().replaceAll("/+$", "") + "/api/v1/query?query="
                    + URLEncoder.encode(promql, StandardCharsets.UTF_8));
            ReadOnlyHttp.Reply reply = http.get(uri);
            if (reply.status() != 200) {
                return "prometheus answered " + reply.status() + ": " + clip(reply.body(), 300);
            }
            return seriesLines(reply.body());
        });
    }

    @Tool("Search the gateway's structured logs. Filters: a trace id, a level (ERROR, WARN, INFO), a word the message "
            + "contains, and how many minutes back. Returns up to 20 lines: time, level, logger, message, trace id.")
    public String searchLogs(@P("a 32-character trace id, or empty") String traceId,
                             @P("ERROR, WARN or INFO, or empty for any") String level,
                             @P("a word the message must contain, or empty") String contains,
                             @P("how many minutes back, 1 to 120") int minutes) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("traceId", nullSafe(traceId));
        args.put("level", nullSafe(level));
        args.put("contains", nullSafe(contains));
        args.put("minutes", minutes);
        return guarded("searchLogs", args, () -> {
            if (traceId != null && !traceId.isBlank() && !TRACE_ID.matcher(traceId).matches()) {
                return "refused: a trace id is 32 lower-case hex characters";
            }
            if (minutes < 1 || minutes > 120) {
                return "refused: minutes must be 1 to 120";
            }
            Instant since = clock.instant().minusSeconds(60L * minutes);
            List<String> lines = new ArrayList<>();
            for (Path file : logFiles()) {
                try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
                    for (String raw : (Iterable<String>) stream::iterator) {
                        if (lines.size() >= MAX_LOG_LINES) {
                            break;
                        }
                        JsonNode n = parse(raw);
                        if (n == null || !matches(n, traceId, level, contains, since)) {
                            continue;
                        }
                        lines.add(n.path("@timestamp").asText() + " " + n.at("/log/level").asText() + " "
                                + n.at("/log/logger").asText() + " trace=" + n.path("traceId").asText("-") + " "
                                + clip(n.path("message").asText(), 300));
                    }
                }
            }
            return lines.isEmpty() ? "no matching log lines" : String.join("\n", lines);
        });
    }

    @Tool("Read the runbook section for an alert: what it means, the first checks, and who owns it.")
    public String readRunbook(@P("the alert name, for example GatewayProviderErrorRatio") String alertName) {
        return guarded("readRunbook", Map.of("alertName", nullSafe(alertName)), () -> {
            if (alertName == null || !ALERT_NAME.matcher(alertName).matches()) {
                return "refused: not an alert name";
            }
            return runbookSection(alertName);
        });
    }

    @Tool("Check a service's readiness endpoint. Services: the names configured for this agent, for example gateway.")
    public String checkHealth(@P("a configured service name") String service) {
        return guarded("checkHealth", Map.of("service", nullSafe(service)), () -> {
            String url = service == null ? null : sources.health().get(service);
            if (url == null) {
                return "refused: unknown service; known: " + String.join(", ", sources.health().keySet());
            }
            ReadOnlyHttp.Reply reply = http.get(URI.create(url));
            return "status " + reply.status() + ": " + clip(reply.body(), 600);
        });
    }

    /** The runbook section, also used verbatim in the email: it is the owner's text, not the model's. */
    public String runbookSection(String alertName) throws IOException {
        List<String> all = Files.readAllLines(sources.runbook(), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String line : all) {
            if (line.startsWith("## ")) {
                if (in) {
                    break;
                }
                in = line.substring(3).strip().equals(alertName);
            }
            if (in) {
                out.append(line).append('\n');
            }
        }
        return out.isEmpty() ? "no runbook section for " + alertName : out.toString().strip();
    }

    private interface Body {
        String get() throws Exception;
    }

    private String guarded(String tool, Map<String, Object> args, Body body) {
        ToolRun run = ToolRun.current();
        if (!policy.allows(run.caller(), tool)) {
            audit.record(run, tool, args, 0, ToolAudit.Outcome.DENIED);
            return "refused: this caller may not use " + tool;
        }
        if (!run.admit()) {
            audit.record(run, tool, args, 0, ToolAudit.Outcome.LIMIT);
            return "refused: the tool-call limit for this run is reached; answer with what you have";
        }
        String result;
        ToolAudit.Outcome outcome = ToolAudit.Outcome.OK;
        try {
            result = clip(body.get(), sources.maxToolResultChars());
        } catch (Exception e) {
            outcome = ToolAudit.Outcome.ERROR;
            result = "tool failed: " + e.getClass().getSimpleName();
        }
        audit.record(run, tool, args, result.length(), outcome);
        return "<tool-result tool=\"" + tool + "\">\n" + result + "\n</tool-result>";
    }

    private List<Path> logFiles() throws IOException {
        Path dir = sources.logDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".log") || p.getFileName().toString().endsWith(".jsonl"))
                    .sorted().toList();
        }
    }

    private static boolean matches(JsonNode n, String traceId, String level, String contains, Instant since) {
        if (traceId != null && !traceId.isBlank() && !traceId.equals(n.path("traceId").asText())) {
            return false;
        }
        if (level != null && !level.isBlank() && !level.equalsIgnoreCase(n.at("/log/level").asText())) {
            return false;
        }
        if (contains != null && !contains.isBlank() && !n.path("message").asText().contains(contains)) {
            return false;
        }
        try {
            return !Instant.parse(n.path("@timestamp").asText()).isBefore(since);
        } catch (RuntimeException unparseable) {
            return false;
        }
    }

    private static JsonNode parse(String line) {
        if (!line.startsWith("{")) {
            return null;
        }
        try {
            return JSON.readTree(line);
        } catch (IOException e) {
            return null;
        }
    }

    private static String seriesLines(String body) throws IOException {
        JsonNode root = JSON.readTree(body);
        JsonNode result = root.at("/data/result");
        if (!result.isArray() || result.isEmpty()) {
            return "no series (the expression matched nothing at this instant)";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode s : result) {
            if (lines.size() >= MAX_SERIES) {
                lines.add("... " + (result.size() - MAX_SERIES) + " more series");
                break;
            }
            lines.add(s.path("metric").toString() + " => " + s.at("/value/1").asText());
        }
        return String.join("\n", lines);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + " [truncated]";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
