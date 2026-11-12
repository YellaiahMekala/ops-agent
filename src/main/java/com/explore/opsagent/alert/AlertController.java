package com.explore.opsagent.alert;

import com.explore.opsagent.config.OpsAgentProperties;
import com.explore.opsagent.triage.TriageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager's webhook (plan 12.1). Bearer token from Alertmanager's {@code http_config}, compared in constant time.
 * The body is validated before anything acts on it; a refusal names fields, never values. Answers 202 at once: triage
 * runs in the background and its result is an email, never this response.
 */
@RestController
public class AlertController {

    private final TriageService triage;
    private final OpsAgentProperties.Webhook webhook;

    public AlertController(TriageService triage, OpsAgentProperties properties) {
        this.triage = triage;
        this.webhook = properties.webhook();
    }

    @PostMapping("/alerts")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AlertmanagerWebhook body) {
        if (!authorised(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "UNAUTHORIZED"));
        }
        List<String> problems = AlertValidation.problems(body, webhook.maxAlerts());
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ALERT", "problems", problems));
        }
        List<TriageService.Intake> intake = triage.accept(body);
        return ResponseEntity.accepted().body(Map.of("intake", intake));
    }

    private boolean authorised(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = header.substring(7).getBytes(StandardCharsets.UTF_8);
        byte[] expected = webhook.token().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected);
    }
}
