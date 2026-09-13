package com.urlshortener.service;

import com.urlshortener.entity.ClickAnalytics;
import com.urlshortener.repository.ClickAnalyticsRepository;
import com.urlshortener.util.UserAgentParser;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ClickAnalyticsRecorderImpl implements ClickAnalyticsRecorder {

    private final ClickAnalyticsRepository clickAnalyticsRepository;

    public ClickAnalyticsRecorderImpl(ClickAnalyticsRepository clickAnalyticsRepository) {
        this.clickAnalyticsRepository = clickAnalyticsRepository;
    }

    @Override
    @Async
    public void recordClick(Long shortUrlId, String referrer, String userAgent) {
        clickAnalyticsRepository.save(new ClickAnalytics(shortUrlId, referrer, UserAgentParser.extractBrowserName(userAgent)));
    }
}
