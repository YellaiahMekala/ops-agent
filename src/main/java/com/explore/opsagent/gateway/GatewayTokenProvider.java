// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.gateway;

/** Bearer token for calls to the gateway. */
public interface GatewayTokenProvider {

    String token();

    /** Drops any cached token (the gateway answered 401), so the next call fetches a new one. */
    void invalidate();
}
