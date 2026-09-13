package com.urlshortener.repository;

import com.urlshortener.repository.ClickAnalyticsRepository.DailyClickCountProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClickAnalyticsRepositoryTest {

    @Mock
    private ClickAnalyticsRepository clickAnalyticsRepository;

    @Test
    void countByShortUrlId_shouldReturnTotalClicks() {
        when(clickAnalyticsRepository.countByShortUrlId(1L)).thenReturn(5L);

        assertEquals(5L, clickAnalyticsRepository.countByShortUrlId(1L));
    }

    @Test
    void findFirstAndLastClickAt_shouldReturnBoundaryTimestamps() {
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime last = LocalDateTime.of(2026, 1, 3, 12, 0);

        when(clickAnalyticsRepository.findFirstClickAt(1L)).thenReturn(Optional.of(first));
        when(clickAnalyticsRepository.findLastClickAt(1L)).thenReturn(Optional.of(last));

        assertEquals(first, clickAnalyticsRepository.findFirstClickAt(1L).orElseThrow());
        assertEquals(last, clickAnalyticsRepository.findLastClickAt(1L).orElseThrow());
    }

    @Test
    void findDailyClickCounts_shouldReturnOrderedBreakdown() {
        DailyClickCountProjection day1 = mock(DailyClickCountProjection.class);
        when(day1.getClickDate()).thenReturn(LocalDate.of(2026, 1, 1));
        when(day1.getClickCount()).thenReturn(3L);

        when(clickAnalyticsRepository.findDailyClickCounts(1L)).thenReturn(List.of(day1));

        List<DailyClickCountProjection> result = clickAnalyticsRepository.findDailyClickCounts(1L);

        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getClickCount());
    }
}
