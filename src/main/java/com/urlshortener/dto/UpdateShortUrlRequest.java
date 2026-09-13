package com.urlshortener.dto;

import jakarta.validation.constraints.Future;

import java.time.LocalDateTime;

/**
 * Partial update: a null field means "leave unchanged," not "clear it." This
 * means an already-set expiresAt cannot be cleared back to null through this
 * endpoint (see Known Limitations in the README) — omitted and explicit-null
 * are indistinguishable here without extra tooling, so one convention had to
 * be picked, and "null = unchanged" matches typical PATCH semantics.
 */
public record UpdateShortUrlRequest(
    Boolean active,

    @Future(message = "expiresAt must be in the future")
    LocalDateTime expiresAt
) {}
