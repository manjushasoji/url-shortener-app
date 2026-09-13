package com.urlshortener.service;

import com.urlshortener.entity.ShortUrl;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.repository.ShortUrlRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class ShortUrlCacheImpl implements ShortUrlCache {

    private final ShortUrlRepository shortUrlRepository;

    public ShortUrlCacheImpl(ShortUrlRepository shortUrlRepository) {
        this.shortUrlRepository = shortUrlRepository;
    }

    @Override
    @Cacheable("shortUrls")
    public CachedShortUrl lookupForRedirect(String shortCode) {
        ShortUrl entity = shortUrlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new ResourceNotFoundException("Short URL not found for code: " + shortCode));

        return new CachedShortUrl(entity.getId(), entity.getOriginalUrl(), entity.isActive(), entity.getExpiresAt());
    }
}
