// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.security;

import com.explore.opsagent.config.OpsAgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Migration mode (ADR-0002): the shared Alertmanager token still opens {@code POST /alerts}, and nothing else, while
 * {@code opsagent.webhook.legacy-enabled} is true. The caller becomes the alert-sender role in the configured legacy
 * tenant. A bearer value that is not the shared token is left to the JWT resource server.
 */
final class LegacyWebhookToken {

    private final OpsAgentProperties.Webhook webhook;

    LegacyWebhookToken(OpsAgentProperties.Webhook webhook) {
        this.webhook = webhook;
    }

    boolean matches(HttpServletRequest request) {
        if (!webhook.legacyEnabled() || webhook.token() == null || webhook.token().isBlank()
                || !"POST".equals(request.getMethod()) || !"/alerts".equals(request.getRequestURI())) {
            return false;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = header.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, webhook.token().getBytes(StandardCharsets.UTF_8)); // constant time
    }

    /** Hides the shared token from the JWT filter, which would otherwise reject it as a malformed JWT. */
    BearerTokenResolver resolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> matches(request) ? null : delegate.resolve(request);
    }

    OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (matches(request)) {
                    SecurityContextHolder.getContext().setAuthentication(new LegacyAuthentication(webhook.tenant()));
                }
                chain.doFilter(request, response);
            }
        };
    }

    static final class LegacyAuthentication extends AbstractAuthenticationToken {
        private final Caller.LegacyPrincipal principal;

        LegacyAuthentication(String tenant) {
            super(List.of(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.ROLE_ALERT_SENDER)));
            this.principal = new Caller.LegacyPrincipal(tenant);
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
