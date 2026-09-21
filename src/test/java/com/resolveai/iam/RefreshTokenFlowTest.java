package com.resolveai.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rotation, reuse detection and the concurrency property behind them, driven over real HTTP.
 */
class RefreshTokenFlowTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        auth.wipe();
        auth.seedTenant("rot");
    }

    @Test
    @DisplayName("rotation issues a new pair and kills the old refresh token")
    void rotationIssuesNewPairAndInvalidatesOld() {
        Map<String, Object> first = auth.login(rest, "rot", "agent");
        String refreshA = (String) first.get("refreshToken");

        ResponseEntity<Map> rotated = refresh(refreshA);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);

        String refreshB = (String) rotated.getBody().get("refreshToken");
        assertThat(refreshB).isNotEqualTo(refreshA);
        assertThat((String) rotated.getBody().get("accessToken"))
                .isNotEqualTo(first.get("accessToken"));

        // A is single-use, so the second presentation is reuse, not merely a stale token.
        assertThat(refresh(refreshA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("reusing a spent token revokes the whole family — including the live successor")
    void reusedTokenRevokesEntireFamily() {
        String refreshA = (String) auth.login(rest, "rot", "agent").get("refreshToken");
        String refreshB = (String) refresh(refreshA).getBody().get("refreshToken");

        // Replaying A is the detection trigger.
        ResponseEntity<Map> replay = refresh(refreshA);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody().get("errorCode")).isEqualTo("TOKEN_REUSE_DETECTED");

        // ── this is the actual test ──────────────────────────────────────────
        // Everything above is setup. Rotation on its own is hygiene; what makes it a
        // defence is that a leak invalidates the attacker's *and* the victim's tokens,
        // because the system cannot tell which of the two presented the replay.
        //
        // This assertion previously failed. The revocation ran inside the same transaction
        // as the exception that reports it, so the rollback undid it and B kept working -
        // a security control that reported success and did nothing. See
        // RefreshTokenFamilyRevoker.
        ResponseEntity<Map> successor = refresh(refreshB);
        assertThat(successor.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(successor.getBody().get("errorCode")).isEqualTo("INVALID_REFRESH_TOKEN");

        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL", Integer.class);
        assertThat(live).as("every token in the family must be revoked").isZero();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenRejected() {
        String refresh = (String) auth.login(rest, "rot", "agent").get("refreshToken");

        // Backdate it in the database rather than waiting seven days.
        jdbc.update("UPDATE refresh_token SET expires_at = ?",
                OffsetDateTime.now().minusMinutes(1));

        ResponseEntity<Map> response = refresh(refresh);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    @DisplayName("logout revokes the family")
    void logoutRevokesFamily() {
        Map<String, Object> session = auth.login(rest, "rot", "agent");
        String refresh = (String) session.get("refreshToken");
        String access = (String) session.get("accessToken");

        // Logout is authenticated, unlike login and refresh: only the holder of a live
        // access token may end a session. An unauthenticated logout endpoint taking a token
        // in the body would let anyone who scraped one log that user out.
        assertThat(logout(refresh, access).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refresh(refresh).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Idempotent: logging out twice is not an error, and telling a caller that a token
        // is unknown would be free information about which tokens are not.
        assertThat(logout(refresh, access).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<Void> logout(String refreshToken, String accessToken) {
        return rest.exchange("/api/v1/auth/logout", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("refreshToken", refreshToken),
                        AuthTestSupport.bearer(accessToken)),
                Void.class);
    }

    @Test
    @DisplayName("two concurrent refreshes of the same token cannot both succeed")
    void concurrentRefreshDoesNotIssueTwoValidTokens() throws Exception {
        String refresh = (String) auth.login(rest, "rot", "agent").get("refreshToken");

        int threads = 8;
        // A latch, not Thread.sleep. A timing-based concurrency test is flaky, gets
        // @Disabled within a week of the first red build, and then protects nothing.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    ResponseEntity<Map> r = refresh(refresh);
                    if (r.getStatusCode().is2xxSuccessful()) {
                        ok.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        // The single-use property is what reuse detection rests on. If two callers could
        // both exchange the same token, a genuine double-click would be indistinguishable
        // from a stolen token, and the detection would either miss real theft or log
        // everyone out at random.
        assertThat(ok.get())
                .as("exactly one of %d concurrent refreshes may succeed", threads)
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> refresh(String token) {
        return rest.postForEntity("/api/v1/auth/refresh",
                Map.of("refreshToken", token), Map.class);
    }
}
