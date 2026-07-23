package com.integration.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════
 * SecurityConfig — OAuth2 Resource Server Security Configuration
 * ═══════════════════════════════════════════════════════════════════
 *
 * Generic OIDC — provider-agnostic (Option C3).
 * Validates JWT tokens issued by any OIDC-compliant IdP.
 * IdP configured via OIDC_ISSUER_URI environment variable —
 * zero code change per customer deployment.
 *
 * ── JWT Role Extraction ─────────────────────────────────────────────
 * Different IdPs put roles in different JWT claim paths:
 *   Keycloak : realm_access.roles (nested object)
 *   Azure AD : roles (flat array)
 *   Okta     : groups (flat array)
 *
 * JwtRolesConverter handles both nested and flat claim structures.
 * Claim name configured via OIDC_ROLES_CLAIM env var.
 *
 * ── Endpoint Access Rules ───────────────────────────────────────────
 *   POST /api/upload/**      → OPERATOR, ADMIN
 *   GET  /api/usecases       → OPERATOR, VIEWER, ADMIN
 *   GET  /api/usecases/**/steps → ADMIN only
 *   GET  /api/results/**     → OPERATOR, VIEWER, ADMIN
 *   GET  /api/audit/**       → OPERATOR, VIEWER, ADMIN
 *   All other /api/**        → authenticated (any role)
 *
 * ── Session ─────────────────────────────────────────────────────────
 * STATELESS — no server-side session. Every request carries a JWT.
 * Standard for REST APIs with OAuth2.
 *
 * ── CORS ────────────────────────────────────────────────────────────
 * Allows requests from FRONTEND_URL only.
 * Configured per deployment — not hardcoded.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${integration.security.roles-claim:roles}")
    private String rolesClaim;

    @Value("${integration.security.role.operator:OPERATOR}")
    private String roleOperator;

    @Value("${integration.security.role.viewer:VIEWER}")
    private String roleViewer;

    @Value("${integration.security.role.admin:ADMIN}")
    private String roleAdmin;

    @Value("${integration.security.cors.allowed-origin:http://localhost:5173}")
    private String allowedOrigin;

    /**
     * Security filter chain — defines endpoint access rules.
     * JWT validation wired automatically via spring.security.oauth2
     * .resourceserver.jwt.issuer-uri in application.properties.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            /*
             * CORS — allow frontend origin only.
             * Pre-flight OPTIONS requests permitted without authentication.
             */
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            /*
             * CSRF disabled — REST API with JWT is stateless.
             * CSRF tokens are only relevant for browser session-based auth.
             */
            .csrf(csrf -> csrf.disable())

            /*
             * Stateless session — no HttpSession created or used.
             * Every request must carry a valid JWT.
             */
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            /*
             * Endpoint access rules — in order of specificity.
             */
            .authorizeHttpRequests(auth -> auth

                /*
                 * Upload — OPERATOR and ADMIN only.
                 * VIEWER cannot upload files.
                 */
                .requestMatchers(HttpMethod.POST, "/api/upload/**")
                    .hasAnyRole(roleOperator, roleAdmin)

                /*
                 * Use case step definitions — ADMIN only.
                 * Sensitive: exposes internal SQL/AQL queries.
                 */
                .requestMatchers(HttpMethod.GET, "/api/usecases/*/steps")
                    .hasRole(roleAdmin)

                /*
                 * All other read endpoints — any authenticated role.
                 */
                .requestMatchers("/api/**")
                    .hasAnyRole(roleOperator, roleViewer, roleAdmin)

                /*
                 * Everything else — deny by default.
                 */
                .anyRequest().denyAll()
            )

            /*
             * OAuth2 Resource Server — validate JWT from OIDC provider.
             * issuer-uri in application.properties points to IdP.
             * Spring Security fetches JWKS from IdP automatically.
             * JwtAuthenticationConverter extracts roles from JWT claims.
             */
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );

        return http.build();
    }

    /**
     * JwtAuthenticationConverter — extracts roles from JWT and maps
     * them to Spring Security GrantedAuthority objects.
     *
     * Handles both flat and nested role claim structures:
     *   Flat   : { "roles": ["OPERATOR", "VIEWER"] }
     *   Nested : { "realm_access": { "roles": ["OPERATOR"] } }
     *
     * Spring Security's hasRole() checks add "ROLE_" prefix automatically.
     * This converter adds "ROLE_" prefix to match.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRolesConverter());
        return converter;
    }

    /**
     * CORS configuration — allows frontend origin only.
     * Configured via FRONTEND_URL environment variable.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        /*
         * Allow only the configured frontend origin.
         * Not wildcard — explicit origin for security.
         */
        config.setAllowedOrigins(List.of(allowedOrigin));

        /*
         * Allow standard HTTP methods used by the frontend.
         */
        config.setAllowedMethods(List.of(
            HttpMethod.GET.name(),
            HttpMethod.POST.name(),
            HttpMethod.OPTIONS.name()
        ));

        /*
         * Allow Authorization header — needed for JWT Bearer token.
         * Allow Content-Type for multipart file upload.
         */
        config.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type",
            "Accept"
        ));

        /*
         * Allow credentials — needed for OIDC PKCE flow.
         */
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     * JwtRolesConverter — Extracts roles from JWT claim
     * ═══════════════════════════════════════════════════════════════
     *
     * Handles both flat and nested role claim structures.
     * Role claim name configured via OIDC_ROLES_CLAIM env var.
     *
     * Examples:
     *   Flat:   claim "roles" = ["OPERATOR"]
     *   Nested: claim "realm_access" = { "roles": ["OPERATOR"] }
     *           rolesClaim should be "realm_access.roles" for nested
     */
    private class JwtRolesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            List<String> roles = List.of();

            /*
             * Support nested claim path using dot notation.
             * e.g. "realm_access.roles" for Keycloak
             *      "roles" for Azure AD / Okta
             */
            if (rolesClaim.contains(".")) {
                String[] parts       = rolesClaim.split("\\.", 2);
                String   parentClaim = parts[0];
                String   childClaim  = parts[1];

                Object parent = jwt.getClaim(parentClaim);
                if (parent instanceof Map) {
                    Object child = ((Map<?, ?>) parent).get(childClaim);
                    if (child instanceof List) {
                        roles = (List<String>) child;
                    }
                }
            } else {
                /*
                 * Flat claim — direct array of role strings.
                 */
                Object claim = jwt.getClaim(rolesClaim);
                if (claim instanceof List) {
                    roles = (List<String>) claim;
                }
            }

            /*
             * Map each role string to a Spring GrantedAuthority.
             * Spring Security hasRole() checks expect "ROLE_" prefix.
             */
            return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
        }
    }
}
