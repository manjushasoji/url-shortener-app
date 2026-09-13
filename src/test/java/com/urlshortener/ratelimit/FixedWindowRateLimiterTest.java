package com.urlshortener.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedWindowRateLimiterTest {

    @Test
    void tryConsume_shouldAllowRequestsUpToLimit_thenReject() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, 60_000, Clock.systemUTC());

        assertTrue(limiter.tryConsume("client-a"));
        assertTrue(limiter.tryConsume("client-a"));
        assertTrue(limiter.tryConsume("client-a"));
        assertFalse(limiter.tryConsume("client-a"));
    }

    @Test
    void tryConsume_shouldTrackClientsIndependently() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60_000, Clock.systemUTC());

        assertTrue(limiter.tryConsume("client-a"));
        assertTrue(limiter.tryConsume("client-b"));
        assertFalse(limiter.tryConsume("client-a"));
        assertFalse(limiter.tryConsume("client-b"));
    }

    @Test
    void tryConsume_shouldResetAfterWindowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60_000, clock);

        assertTrue(limiter.tryConsume("client-a"));
        assertFalse(limiter.tryConsume("client-a"));

        clock.advance(Duration.ofMinutes(1).plusMillis(1));

        assertTrue(limiter.tryConsume("client-a"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }
}
