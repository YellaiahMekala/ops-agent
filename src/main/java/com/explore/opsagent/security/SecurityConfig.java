package com.explore.opsagent.security;

import com.explore.opsagent.config.OpsAgentProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.List;

/**
 * ADR-0002. Stateless JWT resource server (RS256, issuer, audience, expiry, {@code tenant_id}) plus the legacy shared
 * webhook token, switched by {@code opsagent.webhook.legacy-enabled}. Roles come from the top-level {@code roles}
 * claim the platform realm emits and are checked per endpoint. Errors are small JSON bodies with a code and no reason
 * that would help a guesser.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String ROLE_ALERT_SENDER = "OPS_ALERT_SENDER";
    public static final String ROLE_RUN_VIEWER = "OPS_RUN_VIEWER";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OpsAgentProperties properties) throws Exception {
        OpsAgentProperties.Webhook webhook = properties.webhook();
        if (webhook.legacyEnabled() && (webhook.token() == null || webhook.token().isBlank())) {
            throw new IllegalStateException("opsagent.webhook.legacy-enabled is true but OPSAGENT_WEBHOOK_TOKEN is empty");
        }
        if (!webhook.legacyEnabled() && !properties.security().jwtConfigured()) {
            throw new IllegalStateException("no way to authenticate: legacy webhook token is off and no JWT key source is set");
        }
        LegacyWebhookToken legacy = new LegacyWebhookToken(webhook);
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/alerts").hasRole(ROLE_ALERT_SENDER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs", "/api/v1/runs/*").hasRole(ROLE_RUN_VIEWER)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenResolver(legacy.resolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter()))
                        .authenticationEntryPoint(SecurityConfig::unauthorised)
                        .accessDeniedHandler(SecurityConfig::forbidden))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecurityConfig::unauthorised)
                        .accessDeniedHandler(SecurityConfig::forbidden))
                .addFilterBefore(legacy.filter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static void unauthorised(HttpServletRequest req, HttpServletResponse res, AuthenticationException e)
            throws IOException {
        res.setHeader("WWW-Authenticate", "Bearer");
        write(res, 401, "{\"code\":\"UNAUTHORIZED\"}");
    }

    private static void forbidden(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e)
            throws IOException {
        write(res, 403, "{\"code\":\"FORBIDDEN\"}");
    }

    private static void write(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    JwtDecoder jwtDecoder(OpsAgentProperties properties, ResourceLoader loader) {
        OpsAgentProperties.Security security = properties.security();
        if (!security.jwtConfigured()) {
            // Legacy-only mode: every JWT is refused, so a bearer that is not the shared token is a 401.
            return token -> {
                throw new BadJwtException("JWT login is not configured");
            };
        }
        boolean hasJwks = security.jwkSetUri() != null && !security.jwkSetUri().isBlank();
        boolean hasKey = security.publicKeyLocation() != null && !security.publicKeyLocation().isBlank();
        if (hasJwks == hasKey) {
            throw new IllegalStateException(
                    "Set exactly one of opsagent.security.jwk-set-uri or opsagent.security.public-key-location");
        }
        if (security.issuer() == null || security.issuer().isBlank()) {
            throw new IllegalStateException("opsagent.security.issuer is required when JWT is configured");
        }
        NimbusJwtDecoder decoder;
        if (hasJwks) {
            decoder = NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            try (InputStream in = loader.getResource(security.publicKeyLocation()).getInputStream()) {
                RSAPublicKey key = RsaKeyConverters.x509().convert(in);
                decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            } catch (IOException e) {
                throw new IllegalStateException("cannot read the JWT public key", e);
            }
        }
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(security.audience()));
        // JwtTimestampValidator checks exp only when present; a token without exp would never expire.
        OAuth2TokenValidator<Jwt> expiryPresent = new JwtClaimValidator<Object>("exp", exp -> exp != null);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(expiryPresent,
                new JwtTimestampValidator(security.clockSkew()), new JwtIssuerValidator(security.issuer()), audience,
                new TenantClaimValidator()));
        return decoder;
    }

    private static JwtAuthenticationConverter converter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    /** The top-level {@code roles} array becomes {@code ROLE_<name>}. Anything that is not a string is ignored. */
    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object roles = jwt.getClaim("roles");
        if (!(roles instanceof Collection<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
