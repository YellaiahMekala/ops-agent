// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.alert;

import com.explore.opsagent.config.OpsAgentProperties;
import com.explore.opsagent.security.Caller;
import com.explore.opsagent.triage.TriageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager's webhook (plan 12.1). Authenticated by the security filter chain (JWT with the OPS_ALERT_SENDER role, or the legacy shared token, ADR-0002).
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
    public ResponseEntity<Map<String, Object>> receive(Authentication authentication,
                                                       @RequestBody AlertmanagerWebhook body) {
        // Authentication and the OPS_ALERT_SENDER role are enforced by the security filter chain (ADR-0002) before
        // this runs. The tenant is the caller's, from the token.
        Caller caller = Caller.of(authentication);
        List<String> problems = AlertValidation.problems(body, webhook.maxAlerts());
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ALERT", "problems", problems));
        }
        List<TriageService.Intake> intake = triage.accept(body, caller.tenant());
        return ResponseEntity.accepted().body(Map.of("intake", intake));
    }

    /** A body that is not JSON (or not the right shape) gets the same refusal as a failed validation; the parser's
     *  message can quote the input, so it is not passed on. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable() {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_ALERT", "problems", List.of("body is not valid Alertmanager JSON")));
    }
}
