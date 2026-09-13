package com.urlshortener.dto;

import java.time.LocalDateTime;

public record ShortUrlResponse(
    Long id,
    String shortCode,
    String originalUrl,
    Long clickCount,
    boolean active,
    LocalDateTime createdAt,
    LocalDateTime expiresAt
) {
    public ShortUrlResponse(Long id, String shortCode, String originalUrl, Long clickCount, boolean active, LocalDateTime createdAt) {
        this(id, shortCode, originalUrl, clickCount, active, createdAt, null);
    }
}
