package com.urlshortener.service;

import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.InvalidUrlException;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.repository.ClickAnalyticsRepository;
import com.urlshortener.repository.ClickAnalyticsRepository.DailyClickCountProjection;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceImplTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;

    @Mock
    private ClickAnalyticsRepository clickAnalyticsRepository;

    @Mock
    private ClickAnalyticsRecorder clickAnalyticsRecorder;

    @InjectMocks
    private UrlShortenerServiceImpl urlShortenerService;

    @Test
    void createShortUrl_shouldReturnResponse_whenUrlIsValid() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "abc12345");
        ShortUrl savedEntity = new ShortUrl("abc12345", "https://example.com");

        when(shortUrlRepository.existsByShortCode("abc12345")).thenReturn(false);
        when(shortUrlRepository.save(any(ShortUrl.class))).thenReturn(savedEntity);

        ShortUrlResponse response = urlShortenerService.createShortUrl(request);

        assertEquals("abc12345", response.shortCode());
        assertEquals("https://example.com", response.originalUrl());
        assertEquals(Boolean.TRUE, response.active());
    }

    @Test
    void createShortUrl_shouldThrowInvalidUrlException_whenOriginalUrlIsInvalid() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("example.com", null);

        assertThrows(InvalidUrlException.class, () -> urlShortenerService.createShortUrl(request));
    }

    @Test
    void createShortUrl_shouldGenerateCode_whenCustomCodeNotProvided() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", null);

        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = urlShortenerService.createShortUrl(request);

        assertEquals("https://example.com", response.originalUrl());
        assertEquals(8, response.shortCode().length());
        verify(shortUrlRepository, never()).existsByShortCode(any());
    }

    @Test
    void createShortUrl_shouldRetryGeneration_whenGeneratedCodeCollides() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", null);

        when(shortUrlRepository.save(any(ShortUrl.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = urlShortenerService.createShortUrl(request);

        assertEquals("https://example.com", response.originalUrl());
        verify(shortUrlRepository, times(2)).save(any(ShortUrl.class));
    }

    @Test
    void createShortUrl_shouldThrowDuplicateShortCodeException_whenGenerationExhaustsAttempts() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", null);

        when(shortUrlRepository.save(any(ShortUrl.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(DuplicateShortCodeException.class, () -> urlShortenerService.createShortUrl(request));
    }

    @Test
    void createShortUrl_shouldThrowDuplicateShortCodeException_whenCustomCodeCollidesAtSaveTime() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "abc12345");

        when(shortUrlRepository.existsByShortCode("abc12345")).thenReturn(false);
        when(shortUrlRepository.save(any(ShortUrl.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(DuplicateShortCodeException.class, () -> urlShortenerService.createShortUrl(request));
    }

    @Test
    void createShortUrl_shouldPersistExpiresAt_whenProvided() {
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(1);
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "abc12345", expiresAt);

        when(shortUrlRepository.existsByShortCode("abc12345")).thenReturn(false);
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = urlShortenerService.createShortUrl(request);

        assertEquals(expiresAt, response.expiresAt());
    }

    @Test
    void getShortUrlByCode_shouldThrowResourceNotFound_whenCodeDoesNotExist() {
        when(shortUrlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> urlShortenerService.getShortUrlByCode("missing"));
    }

    @Test
    void redirectToOriginalUrl_shouldReturnOriginalUrlAndIncrementClickCount() {
        ShortUrl existing = new ShortUrl("abc12345", "https://example.com");
        existing.setClickCount(1L);
        String chromeUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(existing));
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String originalUrl = urlShortenerService.redirectToOriginalUrl("abc12345", "https://ref.example", chromeUserAgent);

        assertEquals("https://example.com", originalUrl);
        assertEquals(2L, existing.getClickCount());
        verify(clickAnalyticsRecorder).recordClick(existing.getId(), "https://ref.example", chromeUserAgent);
    }

    @Test
    void redirectToOriginalUrl_shouldThrowUrlExpiredException_whenLinkHasExpired() {
        ShortUrl expired = new ShortUrl("abc12345", "https://example.com");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(expired));

        assertThrows(UrlExpiredException.class,
            () -> urlShortenerService.redirectToOriginalUrl("abc12345", null, null));

        verify(shortUrlRepository, never()).save(any());
        verify(clickAnalyticsRecorder, never()).recordClick(any(), any(), any());
    }

    @Test
    void redirectToOriginalUrl_shouldSucceed_whenExpiresAtIsInTheFuture() {
        ShortUrl notYetExpired = new ShortUrl("abc12345", "https://example.com");
        notYetExpired.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(notYetExpired));
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String originalUrl = urlShortenerService.redirectToOriginalUrl("abc12345", null, null);

        assertEquals("https://example.com", originalUrl);
    }

    @Test
    void getClickStats_shouldAggregateTotalsAndDailyBreakdown() {
        ShortUrl existing = new ShortUrl("abc12345", "https://example.com");
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime last = LocalDateTime.of(2026, 1, 2, 15, 30);

        DailyClickCountProjection day1 = mockProjection(LocalDate.of(2026, 1, 1), 3L);
        DailyClickCountProjection day2 = mockProjection(LocalDate.of(2026, 1, 2), 5L);

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(existing));
        when(clickAnalyticsRepository.countByShortUrlId(existing.getId())).thenReturn(8L);
        when(clickAnalyticsRepository.findFirstClickAt(existing.getId())).thenReturn(Optional.of(first));
        when(clickAnalyticsRepository.findLastClickAt(existing.getId())).thenReturn(Optional.of(last));
        when(clickAnalyticsRepository.findDailyClickCounts(existing.getId())).thenReturn(List.of(day1, day2));

        ClickStatsResponse stats = urlShortenerService.getClickStats("abc12345");

        assertEquals("abc12345", stats.shortCode());
        assertEquals(8L, stats.totalClicks());
        assertEquals(first, stats.firstClickAt());
        assertEquals(last, stats.lastClickAt());
        assertEquals(2, stats.dailyBreakdown().size());
        assertEquals(3L, stats.dailyBreakdown().get(0).count());
    }

    @Test
    void getClickStats_shouldThrowResourceNotFound_whenCodeDoesNotExist() {
        when(shortUrlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> urlShortenerService.getClickStats("missing"));
    }

    private DailyClickCountProjection mockProjection(LocalDate date, long count) {
        DailyClickCountProjection projection = org.mockito.Mockito.mock(DailyClickCountProjection.class);
        when(projection.getClickDate()).thenReturn(date);
        when(projection.getClickCount()).thenReturn(count);
        return projection;
    }

    @Test
    void normalizeCustomCode_shouldThrowDuplicateShortCodeException_whenCodeIsInvalid() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "@#$");

        assertThrows(DuplicateShortCodeException.class, () -> urlShortenerService.createShortUrl(request));
    }
}
