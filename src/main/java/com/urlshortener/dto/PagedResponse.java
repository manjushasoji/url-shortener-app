package com.urlshortener.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable, minimal page envelope. Spring Data's Page/PageImpl isn't returned
 * directly because its JSON shape is an implementation detail Spring itself
 * warns against serializing (it changes between versions).
 */
public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public PagedResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
