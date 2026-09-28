package com.resolveai.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsUpToTheLimitWithinTheWindowThenRefuses() {
        var limiter = new InMemoryRateLimiter(new MutableClock());

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("login:1.2.3.4", 3, 60)).isTrue();
        }
        assertThat(limiter.tryAcquire("login:1.2.3.4", 3, 60)).isFalse();
    }

    @Test
    void windowSlidesSoOldHitsExpire() {
        var clock = new MutableClock();
        var limiter = new InMemoryRateLimiter(clock);
        limiter.tryAcquire("k", 2, 60);
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("k", 2, 60);
        assertThat(limiter.tryAcquire("k", 2, 60)).isFalse();

        // The first hit leaves the window; exactly one slot frees up.
        clock.advance(Duration.ofSeconds(31));
        assertThat(limiter.tryAcquire("k", 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("k", 2, 60)).isFalse();
    }

    @Test
    void keysAreIndependent() {
        var limiter = new InMemoryRateLimiter(new MutableClock());
        assertThat(limiter.tryAcquire("login:a", 1, 60)).isTrue();
        assertThat(limiter.tryAcquire("login:a", 1, 60)).isFalse();
        assertThat(limiter.tryAcquire("login:b", 1, 60)).isTrue();
    }
}
