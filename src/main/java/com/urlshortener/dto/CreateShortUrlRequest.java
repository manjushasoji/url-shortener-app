package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateShortUrlRequest(
    @NotBlank(message = "Original URL is required")
    @Pattern(regexp = "^(https?|ftp)://.+", message = "Original URL must be a valid absolute URL")
    String originalUrl,

    String customCode
) {}
