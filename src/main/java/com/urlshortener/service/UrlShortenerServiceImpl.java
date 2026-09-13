package com.urlshortener.service;

import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.DailyClickCount;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.dto.UpdateShortUrlRequest;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.InvalidUpdateRequestException;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.repository.ClickAnalyticsRepository;
import com.urlshortener.repository.ShortUrlRepository;
import com.urlshortener.util.UrlValidator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class UrlShortenerServiceImpl implements UrlShortenerService {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int DEFAULT_CODE_LENGTH = 8;
    private static final int MAX_GENERATION_ATTEMPTS = 5;
    private final ShortUrlRepository shortUrlRepository;
    private final ClickAnalyticsRepository clickAnalyticsRepository;
    private final ClickAnalyticsRecorder clickAnalyticsRecorder;
    private final SecureRandom random = new SecureRandom();

    public UrlShortenerServiceImpl(
        ShortUrlRepository shortUrlRepository,
        ClickAnalyticsRepository clickAnalyticsRepository,
        ClickAnalyticsRecorder clickAnalyticsRecorder) {
        this.shortUrlRepository = shortUrlRepository;
        this.clickAnalyticsRepository = clickAnalyticsRepository;
        this.clickAnalyticsRecorder = clickAnalyticsRecorder;
    }

    @Override
    public ShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
        String originalUrl = UrlValidator.normalizeAndValidate(request.originalUrl());

        return (request.customCode() == null || request.customCode().isBlank())
            ? createWithGeneratedCode(originalUrl, request.expiresAt())
            : createWithCustomCode(originalUrl, normalizeCustomCode(request.customCode()), request.expiresAt());
    }

    /*
     * Each save() attempt below is its own Spring Data-managed transaction (this
     * method is intentionally not @Transactional), so catching a unique-constraint
     * violation from one attempt doesn't poison a later retry. ShortUrl uses
     * GenerationType.IDENTITY, so the INSERT (and any constraint violation) happens
     * synchronously inside save(), not deferred to a later flush.
     */
    private ShortUrlResponse createWithCustomCode(String originalUrl, String shortCode, LocalDateTime expiresAt) {
        if (shortUrlRepository.existsByShortCode(shortCode)) {
            throw new DuplicateShortCodeException("Short code already exists: " + shortCode);
        }

        try {
            return toResponse(shortUrlRepository.save(newShortUrl(shortCode, originalUrl, expiresAt)));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateShortCodeException("Short code already exists: " + shortCode);
        }
    }

    private ShortUrlResponse createWithGeneratedCode(String originalUrl, LocalDateTime expiresAt) {
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return toResponse(shortUrlRepository.save(newShortUrl(generateShortCode(), originalUrl, expiresAt)));
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_GENERATION_ATTEMPTS) {
                    throw new DuplicateShortCodeException(
                        "Unable to generate a unique short code after " + MAX_GENERATION_ATTEMPTS + " attempts");
                }
            }
        }
        throw new IllegalStateException("Unreachable: loop above always returns or throws");
    }

    private ShortUrl newShortUrl(String shortCode, String originalUrl, LocalDateTime expiresAt) {
        ShortUrl shortUrl = new ShortUrl(shortCode, originalUrl);
        shortUrl.setExpiresAt(expiresAt);
        return shortUrl;
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
    public ShortUrlResponse updateShortUrl(String shortCode, UpdateShortUrlRequest request) {
        if (request.active() == null && request.expiresAt() == null) {
            throw new InvalidUpdateRequestException("At least one of active or expiresAt must be provided");
        }

        ShortUrl entity = shortUrlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new ResourceNotFoundException("Short URL not found for code: " + shortCode));

        if (request.active() != null) {
            entity.setActive(request.active());
        }
        if (request.expiresAt() != null) {
            entity.setExpiresAt(request.expiresAt());
        }

        return toResponse(shortUrlRepository.save(entity));
    }

    @Override
    @Transactional
    public String redirectToOriginalUrl(String shortCode, String referrer, String userAgent) {
        ShortUrl entity = shortUrlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new ResourceNotFoundException("Short URL not found for code: " + shortCode));

        if (!entity.isActive()) {
            throw new ResourceNotFoundException("Short URL is inactive: " + shortCode);
        }

        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new UrlExpiredException("Short URL has expired: " + shortCode);
        }

        entity.setClickCount((entity.getClickCount() == null ? 0L : entity.getClickCount()) + 1L);
        shortUrlRepository.save(entity);
        clickAnalyticsRecorder.recordClick(entity.getId(), referrer, userAgent);

        return entity.getOriginalUrl();
    }

    @Override
    @Transactional(readOnly = true)
    public ClickStatsResponse getClickStats(String shortCode) {
        ShortUrl entity = shortUrlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new ResourceNotFoundException("Short URL not found for code: " + shortCode));

        long totalClicks = clickAnalyticsRepository.countByShortUrlId(entity.getId());
        List<DailyClickCount> dailyBreakdown = clickAnalyticsRepository.findDailyClickCounts(entity.getId())
            .stream()
            .map(p -> new DailyClickCount(p.getClickDate(), p.getClickCount()))
            .toList();

        return new ClickStatsResponse(
            entity.getShortCode(),
            totalClicks,
            clickAnalyticsRepository.findFirstClickAt(entity.getId()).orElse(null),
            clickAnalyticsRepository.findLastClickAt(entity.getId()).orElse(null),
            dailyBreakdown
        );
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
            entity.getCreatedAt(),
            entity.getExpiresAt()
        );
    }
}
