package com.urlshortener.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;

public record CreateShortUrlRequest(
    @NotBlank(message = "Original URL is required")
    @Pattern(regexp = "^(https?|ftp)://.+", message = "Original URL must be a valid absolute URL")
    String originalUrl,

    String customCode,

    @Future(message = "expiresAt must be in the future")
    LocalDateTime expiresAt
) {
    public CreateShortUrlRequest(String originalUrl, String customCode) {
        this(originalUrl, customCode, null);
    }
}
