package com.explore.opsagent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Everything ops-agent reads from configuration. Secrets (the webhook token, the model key) come from the environment
 * only; {@code toString()} of the records that hold them never prints them.
 */
@Validated
@ConfigurationProperties(prefix = "opsagent")
public record OpsAgentProperties(@Valid @NotNull Webhook webhook,
                                 @Valid @NotNull Sources sources,
                                 @Valid @NotNull Guardrails guardrails,
                                 @Valid @NotNull Model model,
                                 @Valid @NotNull Mail mail,
                                 @NotEmpty Map<String, List<String>> rbac) {

    /**
     * Alertmanager authenticates with a bearer token ({@code http_config.authorization}). {@code caller} is the
     * identity the run is recorded under and the RBAC role it gets: the alert source, not a person.
     */
    public record Webhook(@NotBlank String token, @NotBlank String caller, @Min(1) @Max(100) int maxAlerts,
                          @Min(1) @Max(64) int queueCapacity) {
        @Override
        public String toString() {
            return "Webhook[token=<redacted>, caller=" + caller + "]";
        }
    }

    /**
     * Where the tools read. Every one is read-only by construction: Prometheus' query API (GET), log files on a
     * read-only mount, the runbook file, and health URLs from an allow-list. Nothing else is reachable.
     */
    public record Sources(@NotBlank String prometheusUrl, @NotNull Path logDir, @NotNull Path runbook,
                          @NotEmpty Map<String, String> health, @NotBlank String dashboardUrl,
                          @NotNull Duration httpTimeout, @Min(256) @Max(65536) int maxToolResultChars) {
    }

    /** Per run: model calls, tokens across them, and wall time. Whichever is reached first stops the run. */
    public record Guardrails(@Min(1) @Max(50) int maxSteps, @Min(100) int maxTokens, @NotNull Duration timeout,
                             @Min(1) @Max(50) int maxToolCalls) {
    }

    public record Model(String apiKey, @NotBlank String name, String baseUrl, @NotNull Duration timeout,
                        @Min(16) @Max(4096) int maxOutputTokens) {
        @Override
        public String toString() {
            return "Model[name=" + name + ", apiKey=<redacted>]";
        }
    }

    /** Mail goes to {@code <owner>@<ownerDomain>}, the alert's owner label: the team, never a person. */
    public record Mail(@NotBlank String from, @NotBlank String ownerDomain, @NotBlank String fallbackOwner) {
    }
}
