package com.explore.opsagent.alert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager's webhook body, version 4 (the format of {@code webhook_configs}). Unknown fields are ignored so a newer
 * Alertmanager does not break intake; what this service relies on is checked by {@link AlertValidation}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerWebhook(String version, String groupKey, String status, String receiver,
                                  Map<String, String> groupLabels, Map<String, String> commonLabels,
                                  Map<String, String> commonAnnotations, String externalURL, List<Alert> alerts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(String status, Map<String, String> labels, Map<String, String> annotations, Instant startsAt,
                        Instant endsAt, String generatorURL, String fingerprint) {

        public String name() {
            return labels.get("alertname");
        }

        public String owner() {
            return labels.get("owner");
        }

        public boolean firing() {
            return "firing".equals(status);
        }
    }
}
