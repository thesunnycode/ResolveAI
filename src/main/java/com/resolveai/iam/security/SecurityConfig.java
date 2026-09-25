package com.resolveai.iam.security;

import java.util.List;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The real filter chain. Replaces {@code PhaseThreeSecurityConfig}, which existed only to
 * keep the actuator reachable while there was nothing to protect.
 *
 * <p><b>Matcher order is load-bearing.</b> Spring Security evaluates
 * {@code authorizeHttpRequests} rules in declaration order and stops at the first match, so
 * an {@code anyRequest().authenticated()} written above the login rule silently locks
 * everyone out of the endpoint they need in order to authenticate. The permits come first,
 * every time.
 *
 * <p><b>CORS is an explicit origin list.</b> {@code allowedOrigins("*")} with
 * {@code allowCredentials(true)} is rejected by the browser anyway, and reaching for the
 * wildcard to make a CORS error go away is how a genuinely permissive configuration ships to
 * production. The list comes from {@code CORS_ALLOWED_ORIGINS}.
 *
 * <p><b>BCrypt strength 12</b>, not the default 10. Roughly four times the work per hash:
 * around 250ms on this hardware, which is invisible on a login and expensive at scale for
 * anyone working through a stolen dump.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final ProblemAuthenticationEntryPoint entryPoint;
    private final ProblemAccessDeniedHandler accessDeniedHandler;
    private final List<String> allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          ProblemAuthenticationEntryPoint entryPoint,
                          ProblemAccessDeniedHandler accessDeniedHandler,
                          @Value("${resolveai.security.cors-allowed-origins:http://localhost:5173}")
                          String corsAllowedOrigins) {
        this.jwtFilter = jwtFilter;
        this.entryPoint = entryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.allowedOrigins = List.of(corsAllowedOrigins.split("\\s*,\\s*"));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        // ── public, and nothing else is ───────────────────────────────
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh").permitAll()
                        // Demo mode (only exists when resolveai.demo.enabled; a 404
                        // otherwise) and first-party product analytics, which has to accept
                        // events from the login page before anyone is signed in.
                        .requestMatchers(HttpMethod.GET, "/api/v1/demo").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/demo/login",
                                "/api/v1/events").permitAll()
                        // CORS preflight carries no Authorization header by definition.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                        // ── everything else ───────────────────────────────────────────
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                "If-Match", "traceparent"));
        // Without this the browser hides them from JavaScript, and the client cannot read
        // the ETag it is required to send back as If-Match.
        config.setExposedHeaders(List.of("ETag", "X-Request-Id", "Retry-After",
                "RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
