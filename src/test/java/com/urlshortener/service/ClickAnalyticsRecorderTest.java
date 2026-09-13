package com.urlshortener.service;

import com.urlshortener.entity.ClickAnalytics;
import com.urlshortener.repository.ClickAnalyticsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * Instantiated directly here (not through Spring), so @Async has no effect in
 * this test — recordClick runs synchronously on the calling thread, which is
 * fine for verifying its business logic. Whether Spring actually dispatches
 * it asynchronously in the running app depends on @EnableAsync + calling it
 * from a different bean, both wired in UrlShortenerServiceImpl/AsyncConfig,
 * and isn't covered by an integration test here.
 */
@ExtendWith(MockitoExtension.class)
class ClickAnalyticsRecorderTest {

    @Mock
    private ClickAnalyticsRepository clickAnalyticsRepository;

    @InjectMocks
    private ClickAnalyticsRecorderImpl clickAnalyticsRecorder;

    @Test
    void recordClick_shouldSaveParsedBrowserNameAndReferrer() {
        String chromeUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

        clickAnalyticsRecorder.recordClick(1L, "https://ref.example", chromeUserAgent);

        ArgumentCaptor<ClickAnalytics> captor = ArgumentCaptor.forClass(ClickAnalytics.class);
        verify(clickAnalyticsRepository).save(captor.capture());
        assertEquals(1L, captor.getValue().getShortUrlId());
        assertEquals("https://ref.example", captor.getValue().getReferrer());
        assertEquals("Chrome", captor.getValue().getUserAgent());
    }

    @Test
    void recordClick_shouldSaveUnknownBrowser_whenUserAgentMissing() {
        clickAnalyticsRecorder.recordClick(2L, null, null);

        ArgumentCaptor<ClickAnalytics> captor = ArgumentCaptor.forClass(ClickAnalytics.class);
        verify(clickAnalyticsRepository).save(captor.capture());
        assertEquals("Unknown", captor.getValue().getUserAgent());
    }
}
