package com.urlshortener.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ClickStatsResponse(
    String shortCode,
    long totalClicks,
    LocalDateTime firstClickAt,
    LocalDateTime lastClickAt,
    List<DailyClickCount> dailyBreakdown
) {}
