package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.common.error.ApiException;
import com.resolveai.platform.idempotency.IdempotencyStore;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code Idempotency-Key} contract.
 *
 * <p>The failure this exists to prevent is small and expensive: a customer's browser
 * retries a slow {@code POST /tickets}, and support now has two identical tickets, two
 * SLA clocks and two agents about to reply to the same person.
 */
class IdempotencyTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        auth.seedTenant("idem");
        customerToken = auth.accessToken(rest, "idem", "customer");
    }

    @Test
    @DisplayName("the same key and the same body returns the identical response and one row")
    void replayReturnsTheSameResponseAndCreatesNothing() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("subject", "Duplicate submit",
                "body", "The browser retried this.");

        ResponseEntity<Map> first = post(body, key);
        ResponseEntity<Map> second = post(body, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Same status, not just same body: a replay that came back 200 where the original
        // was 201 would break any client keying off the status code.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(second.getBody().get("reference")).isEqualTo(first.getBody().get("reference"));

        assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(1);
        // And the reference counter did not advance: the second request never ran.
        assertThat(count("SELECT next_value FROM tenant_sequence WHERE entity_type = 'TICKET'"))
                .isEqualTo(1001);
    }

    @Test
    @DisplayName("the same key with a different body is 422, never a silent replay")
    void sameKeyDifferentBodyIsAConflict() {
        String key = UUID.randomUUID().toString();
        post(Map.of("subject", "First", "body", "Original body"), key);

        ResponseEntity<Map> second = post(
                Map.of("subject", "Second", "body", "Completely different"), key);

        // Replaying the first response here would be the worst possible answer: the client
        // would believe its second, different request had succeeded.
        // Compared by value: Spring 7 renamed the constant to UNPROCESSABLE_CONTENT,
        // tracking the RFC 9110 rename. The number is the contract; the constant is not.
        assertThat(second.getStatusCode().value()).isEqualTo(422);
        assertThat(second.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing or malformed key is 400 IDEMPOTENCY_KEY_REQUIRED")
    void keyIsRequiredAndMustMatchTheFormat() {
        Map<String, Object> body = Map.of("subject", "No key", "body", "Body");

        ResponseEntity<Map> missing = rest.exchange("/api/v1/tickets", HttpMethod.POST,
                new HttpEntity<>(body, AuthTestSupport.bearer(customerToken)), Map.class);
        // Eight characters: syntactically fine, semantically not a key. A short
        // client-chosen value collides between genuinely different requests.
        ResponseEntity<Map> tooShort = post(body, "abc12345");
        ResponseEntity<Map> badChars = post(body, "has spaces and/slashes in it!!");

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(tooShort.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(badChars.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(count("SELECT count(*) FROM ticket")).isZero();
    }

    @Test
    @DisplayName("a durable record is written, so a flushed Redis still refuses a duplicate")
    void theDatabaseIsTheBackstop() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("subject", "Backstop", "body", "Body");
        post(body, key);

        // Simulate Redis losing the entry - eviction, a flush, a restart. Redis is a
        // cache, and a cache that forgets an idempotency record turns the next retry into
        // a duplicate ticket. That is what idempotency_record is for.
        redisFlush();

        ResponseEntity<Map> replayed = post(body, key);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_record")).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed request does not become replayable")
    void aFailureReleasesTheKey() {
        String key = UUID.randomUUID().toString();
        // Over-length body: rejected by Bean Validation before the handler runs.
        ResponseEntity<Map> failed = post(
                Map.of("subject", "Will fail", "body", "x".repeat(20_001)), key);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The client fixes the body and retries with the same key, which is exactly what a
        // client should do. If the failure had been stored, this would replay the 400 for
        // twenty-four hours.
        ResponseEntity<Map> retried = post(Map.of("subject", "Fixed", "body", "Sensible"), key);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * Idempotency fails <b>closed</b>: no Redis, no write.
     *
     * <p>Tested against the store rather than by stopping the container. The Redis
     * container is a JVM-wide singleton shared by every test class in the suite (see
     * {@code IntegrationTestBase}), and stopping it would give it a new host port on
     * restart, breaking every later class with a connection error that looks nothing like
     * the cause. A store pointed at a closed port exercises the same branch.
     *
     * <p>The decision under test: <b>a missed rate limit costs some extra load; a missed
     * idempotency check costs a duplicate ticket or a customer messaged twice.</b> So this
     * one refuses the request, and rate limiting - in Phase 9 - will not.
     */
    @Test
    @DisplayName("Redis unavailable is 503 IDEMPOTENCY_UNAVAILABLE, not a silent pass-through")
    void idempotencyFailsClosed() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", 1);
        try {
            dead.afterPropertiesSet();
            StringRedisTemplate deadTemplate = new StringRedisTemplate(dead);
            deadTemplate.afterPropertiesSet();
            IdempotencyStore store = new IdempotencyStore(deadTemplate, jdbc, objectMapper);

            assertThatThrownBy(() -> store.claim("idem:1:POST /x:k", 1L, "k", "POST /x"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).errorCode().name())
                            .isEqualTo("IDEMPOTENCY_UNAVAILABLE"));
        } finally {
            dead.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(Map<String, Object> body, String key) {
        return rest.exchange("/api/v1/tickets", HttpMethod.POST,
                new HttpEntity<>(body, TicketTestSupport.authed(customerToken, key)), Map.class);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private void redisFlush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
