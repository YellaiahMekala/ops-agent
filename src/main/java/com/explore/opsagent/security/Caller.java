package com.explore.opsagent.security;

import com.explore.opsagent.config.OpsAgentProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling and for which tenant. The tenant comes only from the validated token (or, for the legacy shared
 * token, from configuration). No request header, parameter or body field can set it.
 */
public record Caller(String tenant, String subject, boolean legacy) {

    /** The principal the legacy shared-token filter puts in the security context. */
    public record LegacyPrincipal(String tenant) {
    }

    public static Caller of(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Jwt token = jwt.getToken();
            return new Caller(token.getClaimAsString(TenantClaimValidator.CLAIM), token.getSubject(), false);
        }
        if (authentication != null && authentication.getPrincipal() instanceof LegacyPrincipal legacy) {
            return new Caller(legacy.tenant(), "legacy-webhook-token", true);
        }
        throw new IllegalStateException("no authenticated caller");
    }

    /** The identity a run is recorded under, and its RBAC key for tools: the alert source, as before ADR-0002. */
    public String runCaller(OpsAgentProperties.Webhook webhook) {
        return webhook.caller();
    }
}
