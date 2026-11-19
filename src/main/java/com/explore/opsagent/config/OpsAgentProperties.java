// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
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
                                 @Valid @NotNull Gateway gateway,
                                 @Valid @NotNull Mail mail,
                                 @Valid @NotNull Recovery recovery,
                                 @Valid @NotNull Security security,
                                 @NotEmpty Map<String, List<String>> rbac) {

    /**
     * Alertmanager authenticates with a bearer token ({@code http_config.authorization}). {@code caller} is the
     * identity the run is recorded under and the RBAC role it gets: the alert source, not a person.
     */
    public record Webhook(String token, @NotBlank String caller,
                          @jakarta.validation.constraints.Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String tenant,
                          boolean legacyEnabled, @Min(1) @Max(100) int maxAlerts, @Min(1) @Max(64) int queueCapacity) {
        @Override
        public String toString() {
            return "Webhook[token=<redacted>, caller=" + caller + ", tenant=" + tenant + ", legacyEnabled="
                    + legacyEnabled + "]";
        }
    }

    /**
     * JWT validation (ADR-0002). Set exactly one of {@code jwkSetUri} (Keycloak's certs endpoint) or
     * {@code publicKeyLocation} (a PEM public key, for tests), or neither for legacy-token-only mode.
     */
    public record Security(String issuer, String jwkSetUri, String publicKeyLocation, @NotBlank String audience,
                           @NotNull Duration clockSkew) {
        public boolean jwtConfigured() {
            return (jwkSetUri != null && !jwkSetUri.isBlank()) || (publicKeyLocation != null && !publicKeyLocation.isBlank());
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

    /** How the model is reached (ADR-0003): through the ai-gateway (default), or directly (local development only). */
    public enum Mode { GATEWAY, DIRECT }

    /**
     * {@code apiKey} and {@code baseUrl} are used in {@code DIRECT} mode only and are set only by the {@code direct}
     * profile ({@code application-direct.yml}); the default configuration has no provider key or URL.
     */
    public record Model(@NotNull Mode mode, String apiKey, @NotBlank String name, String baseUrl,
                        @NotNull Duration timeout, @Min(16) @Max(4096) int maxOutputTokens) {
        @Override
        public String toString() {
            return "Model[mode=" + mode + ", name=" + name + ", apiKey=<redacted>]";
        }
    }

    /**
     * The ai-gateway and the Keycloak client ops-agent calls it with (client credentials). {@code clientSecret} comes
     * from the environment only. Used in {@code GATEWAY} mode.
     */
    public record Gateway(String baseUrl, String tokenUri, @NotBlank String clientId, String clientSecret,
                          @NotNull Duration timeout, @NotNull Duration tokenEarlySkew) {
        @Override
        public String toString() {
            return "Gateway[baseUrl=" + baseUrl + ", clientId=" + clientId + ", clientSecret=<redacted>]";
        }
    }

    /** Mail goes to {@code <owner>@<ownerDomain>}, the alert's owner label: the team, never a person. */
    public record Mail(@NotBlank String from, @NotBlank String ownerDomain, @NotBlank String fallbackOwner) {
    }

    /**
     * Run recovery after a restart (ADR-0001). The instance holding a run renews its heartbeat every
     * {@code heartbeatEvery}; a run not renewed for {@code staleAfter} is claimed by a sweeper on any instance, every
     * {@code sweepEvery} and once at startup. A run is resubmitted at most {@code maxAttempts} times in total.
     * {@code instanceId} blank means a fresh id per process.
     */
    public record Recovery(@jakarta.validation.constraints.Size(max = 64) String instanceId, @NotNull Duration heartbeatEvery, @NotNull Duration staleAfter,
                           @NotNull Duration sweepEvery, @Min(1) @Max(10) int maxAttempts,
                           @Min(1) @Max(500) int sweepBatch) {
    }
}
