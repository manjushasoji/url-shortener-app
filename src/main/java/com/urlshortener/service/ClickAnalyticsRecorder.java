package com.urlshortener.service;

import com.urlshortener.entity.ClickAnalytics;
import com.urlshortener.repository.ClickAnalyticsRepository;
import com.urlshortener.util.UserAgentParser;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Records click events off the request thread so a slow or failed analytics
 * write can't add latency to, or fail, the redirect response. Must live in a
 * separate bean from the caller: Spring's @Async proxy only applies across
 * bean boundaries, not to self-invoked methods on the same instance.
 */
@Component
public class ClickAnalyticsRecorder {

    private final ClickAnalyticsRepository clickAnalyticsRepository;

    public ClickAnalyticsRecorder(ClickAnalyticsRepository clickAnalyticsRepository) {
        this.clickAnalyticsRepository = clickAnalyticsRepository;
    }

    @Async
    public void recordClick(Long shortUrlId, String referrer, String userAgent) {
        clickAnalyticsRepository.save(new ClickAnalytics(shortUrlId, referrer, UserAgentParser.extractBrowserName(userAgent)));
    }
}
