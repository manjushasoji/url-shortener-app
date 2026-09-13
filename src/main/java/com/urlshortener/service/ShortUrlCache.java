package com.urlshortener.service;

/**
 * A separate interface (not just a method on UrlShortenerServiceImpl) for the
 * same reason ClickAnalyticsRecorder is: Spring's @Cacheable proxy only
 * intercepts calls arriving from outside the bean it's declared on. If
 * redirectToOriginalUrl called this.lookupForRedirect(...) directly, the
 * annotation would be silently ignored and caching simply wouldn't happen —
 * no error, no warning, just a cache that never engages.
 */
public interface ShortUrlCache {

    /**
     * Throws ResourceNotFoundException for an unknown short code, matching
     * every other lookup in this service — and deliberately not cached:
     * Spring's @Cacheable only stores a value on normal return, so a "not
     * found" result never occupies a cache entry.
     */
    CachedShortUrl lookupForRedirect(String shortCode);
}
