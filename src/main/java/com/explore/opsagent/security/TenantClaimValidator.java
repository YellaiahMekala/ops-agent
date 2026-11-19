// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.regex.Pattern;

/** A token with no usable {@code tenant_id} is refused at the door, so no request exists without a tenant to scope by. */
final class TenantClaimValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM = "tenant_id";
    private static final Pattern TENANT = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object value = jwt.getClaim(CLAIM);
        if (value instanceof String s && TENANT.matcher(s).matches()) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "tenant_id missing or malformed", null));
    }
}
