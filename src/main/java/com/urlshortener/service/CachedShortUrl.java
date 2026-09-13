package com.urlshortener.service;

import java.time.LocalDateTime;

/**
 * Deliberately not the JPA entity: caching the mutable ShortUrl entity
 * directly would either let click-count increments silently leak into the
 * cached copy (Caffeine stores references in-memory) or go stale relative to
 * it, depending on how the cache and the write path interact — neither is
 * safe. This is a small immutable snapshot of only the fields the redirect
 * decision actually needs; clickCount is deliberately excluded and always
 * updated with a direct, uncached write (see ShortUrlRepository.incrementClickCount).
 */
public record CachedShortUrl(Long id, String originalUrl, boolean active, LocalDateTime expiresAt) {}
