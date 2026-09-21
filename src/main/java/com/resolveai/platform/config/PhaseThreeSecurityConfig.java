package com.resolveai.platform.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A deliberately temporary security chain, replaced wholesale by the real one in
 * <a href="../../../../../../../docs/planning/09-TASK-BREAKDOWN-PHASE-4.md">Phase 4</a>.
 *
 * <p>Phase 3 has no users, no JWTs and no endpoints to protect. Spring Security's default —
 * HTTP Basic over every path with a password printed to the console at startup — is wrong in
 * two ways here: it makes {@code /actuator/prometheus} return 401 to a scraper that has no
 * credentials to offer, and it would force every Phase 3 test to carry a basic-auth header
 * that gets deleted again in Phase 4.
 *
 * <p><b>{@code @Profile("!prod")} is the safety catch, and it is the reason this class is
 * safe to write.</b> Not a comment, not a TODO — under the {@code prod} profile this bean
 * does not exist, so Boot's locked-down default applies instead and a forgotten permissive
 * chain cannot reach production. Forgetting is the normal outcome for temporary code; making
 * the forgetting harmless is the only reliable mitigation.
 */
@Configuration
@Profile("!prod")
public class PhaseThreeSecurityConfig {

    @Bean
    SecurityFilterChain phaseThreeFilterChain(HttpSecurity http) throws Exception {
        http
                // No browser sessions and no CSRF token flow: this is a token-authenticated
                // JSON API. Leaving CSRF on would reject every POST in the Postman collection
                // for a reason that does not apply to it.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        // Health, info and the Prometheus scrape endpoint. The prod profile
                        // narrows the exposed set and hides health detail; here they are open
                        // so the smoke test and a local Prometheus can reach them.
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll()
                        // TODO Phase 4 Task 8: replace with the JWT filter and role rules from
                        // doc 05. Until an endpoint exists that has something to protect, this
                        // is honest about protecting nothing.
                        .anyRequest().permitAll());
        return http.build();
    }
}
