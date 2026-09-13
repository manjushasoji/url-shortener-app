package com.urlshortener.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * A single Caffeine cache ("shortUrls") backing ShortUrlCache. TTL is a
 * safety net, not the primary correctness mechanism: @CacheEvict on
 * updateShortUrl handles the common case (an admin deactivates/changes a
 * link, the next redirect re-reads fresh) correctly and immediately. The TTL
 * exists for what @CacheEvict can't cover on a single instance — a future
 * write path that bypasses updateShortUrl — and, more importantly, for what
 * it can't cover at all: if this ever runs on more than one instance,
 * @CacheEvict only clears the instance that handled the write, so other
 * instances would keep serving a stale entry until their own TTL expires.
 * See Known Limitations in the README.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final String CACHE_NAME = "shortUrls";
    private static final long MAX_ENTRIES = 10_000;
    private static final Duration TIME_TO_LIVE = Duration.ofMinutes(5);

    @Bean
    public CacheManagerCustomizer<CaffeineCacheManager> shortUrlCacheCustomizer() {
        return cacheManager -> {
            cacheManager.setCacheNames(List.of(CACHE_NAME));
            cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(TIME_TO_LIVE));
        };
    }
}
