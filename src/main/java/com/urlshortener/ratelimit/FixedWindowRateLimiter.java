package com.urlshortener.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A simple per-key fixed-window rate limiter. Not as smooth as a token-bucket
 * algorithm (bursts are possible right at a window boundary), but it needs no
 * external dependency and is easy to reason about for a single-instance service.
 *
 * Expired windows are purged lazily: at most once per window duration, the
 * first tryConsume call after that interval sweeps out every entry whose window
 * has already elapsed. Without this the map would grow by one entry per distinct
 * client key for the life of the process and never shrink.
 */
public class FixedWindowRateLimiter {

    private final int maxRequestsPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastPurgeMillis;

    public FixedWindowRateLimiter(int maxRequestsPerWindow, long windowMillis) {
        this(maxRequestsPerWindow, windowMillis, Clock.systemUTC());
    }

    FixedWindowRateLimiter(int maxRequestsPerWindow, long windowMillis, Clock clock) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMillis = windowMillis;
        this.clock = clock;
        this.lastPurgeMillis = new AtomicLong(clock.millis());
    }

    public boolean tryConsume(String key) {
        long now = clock.millis();
        purgeExpiredIfDue(now);
        Window window = windows.compute(key, (k, existing) ->
            (existing == null || now - existing.startMillis >= windowMillis) ? new Window(now) : existing);
        return window.count.incrementAndGet() <= maxRequestsPerWindow;
    }

    /** Number of client keys currently held in memory. Exposed for tests. */
    int trackedKeyCount() {
        return windows.size();
    }

    /*
     * The CAS ensures only one caller performs a given sweep; every other thread
     * that observes the same stale lastPurgeMillis loses the race and skips it.
     * The removeIf predicate re-checks expiry per entry, so a window that was
     * refreshed by a concurrent tryConsume between the check and the removal is
     * simply left in place (compute() and removeIf() are both atomic per key).
     */
    private void purgeExpiredIfDue(long now) {
        long lastPurge = lastPurgeMillis.get();
        if (now - lastPurge < windowMillis) {
            return;
        }
        if (lastPurgeMillis.compareAndSet(lastPurge, now)) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startMillis >= windowMillis);
        }
    }

    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count = new AtomicInteger(0);

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
