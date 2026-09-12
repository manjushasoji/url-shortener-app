package com.urlshortener.dto;

import java.time.LocalDateTime;

public record ShortUrlResponse(
    Long id,
    String shortCode,
    String originalUrl,
    Long clickCount,
    boolean active,
    LocalDateTime createdAt
) {}
