// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.gateway;

/**
 * A model call through the gateway that did not produce a model answer. {@link Kind} says what the run does next; the
 * message and fields carry the HTTP status and the gateway's problem {@code code} only, never a body, a prompt or a
 * token.
 */
public class GatewayCallException extends RuntimeException {

    public enum Kind {
        /** 400, 401, 403, 422 and other 4xx: the request itself is refused. Retrying the same request cannot help. */
        REJECTED,
        /** 429 AGENT_RUN_LIMIT: the gateway's per-run step or token budget is spent. The run stops cleanly. */
        RUN_LIMIT,
        /** 5xx, timeout, connection or token-endpoint failure: the outcome of the call is not known. Never redone. */
        UNAVAILABLE
    }

    private final Kind kind;
    private final int httpStatus;
    private final String code;

    public GatewayCallException(Kind kind, int httpStatus, String code, Throwable cause) {
        super("gateway call failed: " + kind + " status=" + httpStatus + " code=" + code, cause, false, false);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    /** Value for {@code triage_run.stop_reason} (VARCHAR 32). */
    public String stopReason() {
        String reason = switch (kind) {
            case RUN_LIMIT -> "GATEWAY_RUN_LIMIT";
            case REJECTED -> "GATEWAY_" + code;
            case UNAVAILABLE -> "GATEWAY_UNAVAILABLE";
        };
        return reason.length() > 32 ? reason.substring(0, 32) : reason;
    }
}
