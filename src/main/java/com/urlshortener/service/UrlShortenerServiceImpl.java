package com.urlshortener.service;

import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.repository.ShortUrlRepository;
import com.urlshortener.util.UrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;

@Service
public class UrlShortenerServiceImpl implements UrlShortenerService {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int DEFAULT_CODE_LENGTH = 8;
    private final ShortUrlRepository shortUrlRepository;
    private final SecureRandom random = new SecureRandom();

    public UrlShortenerServiceImpl(ShortUrlRepository shortUrlRepository) {
        this.shortUrlRepository = shortUrlRepository;
    }

    @Override
    @Transactional
    public ShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
        String originalUrl = UrlValidator.normalizeAndValidate(request.originalUrl());

        String shortCode = (request.customCode() == null || request.customCode().isBlank())
            ? generateShortCode()
            : normalizeCustomCode(request.customCode());

        if (shortUrlRepository.existsByShortCode(shortCode)) {
            throw new DuplicateShortCodeException("Short code already exists: " + shortCode);
        }

        ShortUrl shortUrl = new ShortUrl(shortCode, originalUrl);
        ShortUrl saved = shortUrlRepository.save(shortUrl);

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ShortUrlResponse getShortUrlByCode(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new ResourceNotFoundException("Short code is required");
        }

        ShortUrl entity = shortUrlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new ResourceNotFoundException("Short URL not found for code: " + shortCode));

        return toResponse(entity);
    }

    @Override
    @Transactional
    public String redirectToOriginalUrl(String shortCode) {
        ShortUrl entity = shortUrlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new ResourceNotFoundException("Short URL not found for code: " + shortCode));

        if (!entity.isActive()) {
            throw new ResourceNotFoundException("Short URL is inactive: " + shortCode);
        }

        entity.setClickCount((entity.getClickCount() == null ? 0L : entity.getClickCount()) + 1L);
        shortUrlRepository.save(entity);

        return entity.getOriginalUrl();
    }

    private String generateShortCode() {
        StringBuilder code = new StringBuilder(DEFAULT_CODE_LENGTH);
        for (int i = 0; i < DEFAULT_CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private String normalizeCustomCode(String customCode) {
        String normalized = customCode.trim();
        if (normalized.isEmpty()) {
            throw new DuplicateShortCodeException("Custom short code cannot be empty");
        }

        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-zA-Z0-9-]{3,20}")) {
            throw new DuplicateShortCodeException("Custom short code can contain only letters, numbers, and hyphen, 3-20 characters");
        }

        return normalized;
    }

    private ShortUrlResponse toResponse(ShortUrl entity) {
        return new ShortUrlResponse(
            entity.getId(),
            entity.getShortCode(),
            entity.getOriginalUrl(),
            entity.getClickCount(),
            entity.isActive(),
            entity.getCreatedAt()
        );
    }
}
