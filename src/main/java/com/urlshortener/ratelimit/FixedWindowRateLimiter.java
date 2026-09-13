package com.urlshortener.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple per-key fixed-window rate limiter. Not as smooth as a token-bucket
 * algorithm (bursts are possible right at a window boundary), but it needs no
 * external dependency and is easy to reason about for a single-instance service.
 */
public class FixedWindowRateLimiter {

    private final int maxRequestsPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxRequestsPerWindow, long windowMillis) {
        this(maxRequestsPerWindow, windowMillis, Clock.systemUTC());
    }

    FixedWindowRateLimiter(int maxRequestsPerWindow, long windowMillis, Clock clock) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    public boolean tryConsume(String key) {
        long now = clock.millis();
        Window window = windows.compute(key, (k, existing) ->
            (existing == null || now - existing.startMillis >= windowMillis) ? new Window(now) : existing);
        return window.count.incrementAndGet() <= maxRequestsPerWindow;
    }

    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count = new AtomicInteger(0);

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
