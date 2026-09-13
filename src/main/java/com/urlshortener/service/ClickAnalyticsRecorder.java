package com.urlshortener.service;

/**
 * Records click events off the request thread so a slow or failed analytics
 * write can't add latency to, or fail, the redirect response.
 *
 * <p>Deliberately an interface, not just {@link ClickAnalyticsRecorderImpl}
 * directly: Spring's {@code @Async} proxy needs a bean boundary between
 * caller and callee to apply (self-invocation on the same instance bypasses
 * it), and mocking this in tests as an interface avoids Mockito's inline
 * mock maker entirely — mocking a concrete class needs bytecode
 * instrumentation via a self-attached Java agent, which some JDK/CI
 * combinations refuse even with {@code -Djdk.attach.allowAttachSelf=true}.
 * An interface mock is just a plain JDK dynamic proxy.
 */
public interface ClickAnalyticsRecorder {

    void recordClick(Long shortUrlId, String referrer, String userAgent);
}
