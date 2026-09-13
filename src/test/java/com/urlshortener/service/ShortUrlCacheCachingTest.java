package com.urlshortener.service;

import com.urlshortener.config.CacheConfig;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a real Spring context (CacheConfig's actual CaffeineCacheManager and
 * a genuine AOP proxy around ShortUrlCacheImpl) instead of plain Mockito,
 * because caching IS the behavior under test here: calling the method
 * directly on a Mockito-constructed instance would never go through the
 * @Cacheable proxy at all — the same self-invocation trap this project
 * already hit once with @Async (see ClickAnalyticsRecorder).
 *
 * CacheConfig itself only defines a CacheManagerCustomizer bean — in the
 * real app, Spring Boot's auto-configuration is what actually creates the
 * CaffeineCacheManager bean and applies that customizer to it. This
 * lightweight @SpringJUnitConfig context doesn't run that auto-configuration,
 * so TestConfig builds the CaffeineCacheManager itself and applies
 * CacheConfig's real customizer bean to it, to keep exercising the actual
 * TTL/size settings rather than a stand-in.
 *
 * Not covered here: @CacheEvict on UrlShortenerServiceImpl.updateShortUrl
 * actually evicting this same cache end-to-end — that would need
 * UrlShortenerServiceImpl itself wired into a cache-enabled context, which
 * pulls in enough other collaborators (ClickAnalyticsRepository,
 * ClickAnalyticsRecorder) that it wasn't judged worth the added test-only
 * wiring for what the annotation itself already makes straightforward to
 * verify is correctly applied by inspection.
 */
@SpringJUnitConfig
class ShortUrlCacheCachingTest {

    @Configuration
    @Import(CacheConfig.class)
    static class TestConfig {

        @Bean
        CacheManager cacheManager(CacheManagerCustomizer<CaffeineCacheManager> customizer) {
            CaffeineCacheManager cacheManager = new CaffeineCacheManager();
            customizer.customize(cacheManager);
            return cacheManager;
        }

        @Bean
        ShortUrlRepository shortUrlRepository() {
            return mock(ShortUrlRepository.class);
        }

        @Bean
        ShortUrlCache shortUrlCache(ShortUrlRepository shortUrlRepository) {
            return new ShortUrlCacheImpl(shortUrlRepository);
        }
    }

    @Autowired
    private ShortUrlCache shortUrlCache;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @Test
    void lookupForRedirect_shouldOnlyHitRepositoryOnce_forRepeatedLookups() {
        ShortUrl entity = new ShortUrl("abc12345", "https://example.com");
        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(entity));

        shortUrlCache.lookupForRedirect("abc12345");
        shortUrlCache.lookupForRedirect("abc12345");
        CachedShortUrl result = shortUrlCache.lookupForRedirect("abc12345");

        verify(shortUrlRepository, times(1)).findByShortCode("abc12345");
        assertEquals("https://example.com", result.originalUrl());
    }

    @Test
    void lookupForRedirect_shouldNotCacheNotFoundResults() {
        when(shortUrlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> shortUrlCache.lookupForRedirect("missing"));
        assertThrows(ResourceNotFoundException.class, () -> shortUrlCache.lookupForRedirect("missing"));

        verify(shortUrlRepository, times(2)).findByShortCode("missing");
    }

    @Test
    void lookupForRedirect_shouldCacheDifferentShortCodesIndependently() {
        ShortUrl first = new ShortUrl("abc12345", "https://example.com");
        ShortUrl second = new ShortUrl("xyz98765", "https://example.org");
        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(first));
        when(shortUrlRepository.findByShortCode("xyz98765")).thenReturn(Optional.of(second));

        shortUrlCache.lookupForRedirect("abc12345");
        shortUrlCache.lookupForRedirect("xyz98765");
        shortUrlCache.lookupForRedirect("abc12345");
        shortUrlCache.lookupForRedirect("xyz98765");

        verify(shortUrlRepository, times(1)).findByShortCode("abc12345");
        verify(shortUrlRepository, times(1)).findByShortCode("xyz98765");
    }
}
