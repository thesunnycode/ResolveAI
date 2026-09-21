package com.resolveai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The first real integration test: the platform works end to end against a real database.
 *
 * <p>Test 3 is the one that earns its place. The other two prove the wiring; test 3 proves
 * the <b>contract</b> in doc 05 is implemented rather than merely written down — that a
 * validation failure produces {@code application/problem+json} carrying an
 * {@code errorCode}, a {@code traceId} and a populated {@code errors[]}, and not Spring's
 * own thinner body.
 */
class PlatformSmokeTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthTestSupport auth;

    private String token;

    @org.junit.jupiter.api.BeforeEach
    void seedAndLogIn() {
        auth.wipe();
        auth.seedTenant("smoke");
        token = auth.accessToken(rest, "smoke", "agent");
    }

    @Test
    @DisplayName("health reports UP with db, redis and outboxLag")
    void healthEndpointReturnsUp() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        // Naming the three explicitly: a health endpoint that returns UP because it checks
        // nothing is the classic false green, and this is the assertion that rules it out.
        assertThat(components).containsKeys("db", "redis", "outboxLag");
    }

    @Test
    @DisplayName("Flyway applied all 9 migrations against a truly empty database")
    void flywayAppliedAllMigrations() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(9);

        Integer tables = jdbc.queryForObject("""
                SELECT count(*) FROM pg_tables
                 WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, Integer.class);
        assertThat(tables).isEqualTo(40);

        // The partial indexes carry the correctness properties the whole system rests on.
        // A WHERE clause silently dropped during a migration edit creates an index that
        // looks right and enforces the wrong thing, so the count is asserted here too.
        Integer partialIndexes = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname = 'public' AND indexdef ILIKE '%WHERE%'
                """, Integer.class);
        assertThat(partialIndexes).isEqualTo(23);
    }

    @Test
    @DisplayName("a validation failure returns RFC 7807 with errorCode, traceId and errors[]")
    void validationErrorReturnsProblemJson() {
        // Blank subject, missing body: two violations, so errors[] cannot pass by accident
        // with a single-element array.
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/__test__/echo", HttpMethod.POST,
                new HttpEntity<>(Map.of("subject", "  "), AuthTestSupport.bearer(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .as("errors must be application/problem+json, not application/json")
                        .isTrue());

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("errorCode")).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("type")).isEqualTo("https://resolveai.dev/errors/validation");
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("instance")).isEqualTo("/api/v1/__test__/echo");
        // traceId and timestamp are what make an error report actionable, and they are the
        // two fields Boot's own ProblemDetail body omits - so asserting them also pins the
        // advice ordering that keeps our handler in front of Boot's.
        assertThat((String) body.get("traceId")).isNotBlank().isNotEqualTo("unavailable");
        assertThat(body.get("timestamp")).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors).extracting(e -> e.get("field"))
                .containsExactlyInAnyOrder("subject", "body");
        assertThat(errors).allSatisfy(e -> assertThat(e.get("code")).isNotNull());
    }

    @Test
    @DisplayName("an unmatched path returns problem+json, not a Spring 404 page")
    void unmatchedPathReturnsProblemJson() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/does-not-exist", HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("errorCode")).isEqualTo("NOT_FOUND");
        assertThat((String) response.getBody().get("traceId")).isNotBlank();
    }

    @Test
    @DisplayName("every response carries X-Request-Id, and each one is distinct")
    void requestIdsAreUniquePerRequest() {
        // Unauthenticated on purpose: MdcFilter runs before security, so even a 401 must
        // carry the header. If it did not, the responses hardest to debug would be the ones
        // with no correlation id.
        String first = rest.getForEntity("/api/v1/does-not-exist", Map.class)
                .getHeaders().getFirst("X-Request-Id");
        String second = rest.getForEntity("/api/v1/does-not-exist", Map.class)
                .getHeaders().getFirst("X-Request-Id");

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        // A leaked MDC on a pooled thread would give the second request the first one's id,
        // which is worse than no id at all: it is confidently wrong and sends whoever is
        // debugging to read the wrong request.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("an unauthenticated API call is 401 problem+json, not a container error page")
    void unauthenticatedRequestIsProblemJson() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/auth/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        // Spring Security rejects inside the filter chain, before any controller and so
        // before @RestControllerAdvice. Without a custom entry point this one status - the
        // one a client meets first - would be the only place the error contract did not hold.
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("errorCode")).isEqualTo("UNAUTHORIZED");
        assertThat((String) response.getBody().get("traceId")).isNotBlank();
    }
}
