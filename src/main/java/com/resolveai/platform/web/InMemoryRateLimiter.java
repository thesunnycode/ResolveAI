package com.resolveai.platform.web;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A sliding-window limit for the few public endpoints that are not authentication: the
 * demo's one-click login and the product-analytics intake.
 *
 * <p>In memory, per instance: the live demo runs on one dyno, and what this guards against
 * is one visitor (or one script) burning the LLM budget or flooding the events table - not
 * a distributed attack, which the platform-wide limiter planned for Phase 10 is the answer
 * to. A restart forgetting the counters is acceptable at that scale.
 */
@Component
public class InMemoryRateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter() {
        this(Clock.systemUTC());
    }

    InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @return whether this call is within {@code limit} per {@code windowSeconds} for {@code key} */
    public boolean tryAcquire(String key, int limit, long windowSeconds) {
        long now = clock.millis();
        long cutoff = now - windowSeconds * 1000;
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
