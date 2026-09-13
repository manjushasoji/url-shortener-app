package com.urlshortener.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ClickStatsResponse(
    String shortCode,
    long totalClicks,
    LocalDateTime firstClickAt,
    LocalDateTime lastClickAt,
    List<DailyClickCount> dailyBreakdown
) {
    /** Defensive copy so the response is genuinely immutable once built (a null list becomes empty). */
    public ClickStatsResponse {
        dailyBreakdown = dailyBreakdown == null ? List.of() : List.copyOf(dailyBreakdown);
    }
}
