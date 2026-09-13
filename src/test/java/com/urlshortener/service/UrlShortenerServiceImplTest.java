package com.urlshortener.service;

import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.dto.UpdateShortUrlRequest;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.InvalidShortCodeException;
import com.urlshortener.exception.InvalidUpdateRequestException;
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

    @Mock
    private ShortUrlCache shortUrlCache;

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
    void updateShortUrl_shouldDeactivate_whenActiveFalseProvided() {
        ShortUrl existing = new ShortUrl("abc12345", "https://example.com");

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(existing));
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = urlShortenerService.updateShortUrl("abc12345", new UpdateShortUrlRequest(false, null));

        assertEquals(false, response.active());
    }

    @Test
    void updateShortUrl_shouldUpdateExpiresAt_whenProvided() {
        ShortUrl existing = new ShortUrl("abc12345", "https://example.com");
        LocalDateTime newExpiresAt = LocalDateTime.now().plusDays(3);

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(existing));
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = urlShortenerService.updateShortUrl("abc12345", new UpdateShortUrlRequest(null, newExpiresAt));

        assertEquals(newExpiresAt, response.expiresAt());
        assertEquals(true, response.active());
    }

    @Test
    void updateShortUrl_shouldUpdateBothFields_whenBothProvided() {
        ShortUrl existing = new ShortUrl("abc12345", "https://example.com");
        LocalDateTime newExpiresAt = LocalDateTime.now().plusDays(3);

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(existing));
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = urlShortenerService.updateShortUrl("abc12345", new UpdateShortUrlRequest(false, newExpiresAt));

        assertEquals(false, response.active());
        assertEquals(newExpiresAt, response.expiresAt());
    }

    @Test
    void updateShortUrl_shouldLeaveExpiresAtUnchanged_whenOnlyActiveProvided() {
        ShortUrl existing = new ShortUrl("abc12345", "https://example.com");
        LocalDateTime originalExpiresAt = LocalDateTime.now().plusDays(1);
        existing.setExpiresAt(originalExpiresAt);

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(existing));
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = urlShortenerService.updateShortUrl("abc12345", new UpdateShortUrlRequest(false, null));

        assertEquals(originalExpiresAt, response.expiresAt());
    }

    @Test
    void updateShortUrl_shouldThrowInvalidUpdateRequestException_whenNoFieldsProvided() {
        assertThrows(InvalidUpdateRequestException.class,
            () -> urlShortenerService.updateShortUrl("abc12345", new UpdateShortUrlRequest(null, null)));

        verify(shortUrlRepository, never()).findByShortCode(any());
    }

    @Test
    void updateShortUrl_shouldThrowResourceNotFound_whenCodeDoesNotExist() {
        when(shortUrlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> urlShortenerService.updateShortUrl("missing", new UpdateShortUrlRequest(false, null)));
    }

    @Test
    void redirectToOriginalUrl_shouldReturnOriginalUrlAndIncrementClickCount() {
        CachedShortUrl cached = new CachedShortUrl(1L, "https://example.com", true, null);
        String chromeUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

        when(shortUrlCache.lookupForRedirect("abc12345")).thenReturn(cached);

        String originalUrl = urlShortenerService.redirectToOriginalUrl("abc12345", "https://ref.example", chromeUserAgent);

        assertEquals("https://example.com", originalUrl);
        verify(shortUrlRepository).incrementClickCount(1L);
        verify(clickAnalyticsRecorder).recordClick(1L, "https://ref.example", chromeUserAgent);
    }

    @Test
    void redirectToOriginalUrl_shouldThrowResourceNotFound_whenLinkIsInactive() {
        CachedShortUrl cached = new CachedShortUrl(1L, "https://example.com", false, null);

        when(shortUrlCache.lookupForRedirect("abc12345")).thenReturn(cached);

        assertThrows(ResourceNotFoundException.class,
            () -> urlShortenerService.redirectToOriginalUrl("abc12345", null, null));

        verify(shortUrlRepository, never()).incrementClickCount(any());
        verify(clickAnalyticsRecorder, never()).recordClick(any(), any(), any());
    }

    @Test
    void redirectToOriginalUrl_shouldThrowUrlExpiredException_whenLinkHasExpired() {
        CachedShortUrl cached = new CachedShortUrl(1L, "https://example.com", true, LocalDateTime.now().minusMinutes(1));

        when(shortUrlCache.lookupForRedirect("abc12345")).thenReturn(cached);

        assertThrows(UrlExpiredException.class,
            () -> urlShortenerService.redirectToOriginalUrl("abc12345", null, null));

        verify(shortUrlRepository, never()).incrementClickCount(any());
        verify(clickAnalyticsRecorder, never()).recordClick(any(), any(), any());
    }

    @Test
    void redirectToOriginalUrl_shouldSucceed_whenExpiresAtIsInTheFuture() {
        CachedShortUrl cached = new CachedShortUrl(1L, "https://example.com", true, LocalDateTime.now().plusDays(1));

        when(shortUrlCache.lookupForRedirect("abc12345")).thenReturn(cached);

        String originalUrl = urlShortenerService.redirectToOriginalUrl("abc12345", null, null);

        assertEquals("https://example.com", originalUrl);
    }

    @Test
    void redirectToOriginalUrl_shouldPropagateResourceNotFound_whenCacheThrowsForUnknownCode() {
        when(shortUrlCache.lookupForRedirect("missing"))
            .thenThrow(new ResourceNotFoundException("Short URL not found for code: missing"));

        assertThrows(ResourceNotFoundException.class,
            () -> urlShortenerService.redirectToOriginalUrl("missing", null, null));
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
    void normalizeCustomCode_shouldThrowInvalidShortCodeException_whenCodeIsInvalid() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "@#$");

        assertThrows(InvalidShortCodeException.class, () -> urlShortenerService.createShortUrl(request));
        verify(shortUrlRepository, never()).save(any(ShortUrl.class));
    }

    @Test
    void normalizeCustomCode_shouldThrowInvalidShortCodeException_whenCodeIsTooShort() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "ab");

        assertThrows(InvalidShortCodeException.class, () -> urlShortenerService.createShortUrl(request));
        verify(shortUrlRepository, never()).save(any(ShortUrl.class));
    }
}
