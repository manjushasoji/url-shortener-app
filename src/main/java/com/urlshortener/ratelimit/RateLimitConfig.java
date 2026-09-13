package com.urlshortener.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    private static final int MAX_REQUESTS_PER_WINDOW = 30;
    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    @Bean
    public FixedWindowRateLimiter fixedWindowRateLimiter() {
        return new FixedWindowRateLimiter(MAX_REQUESTS_PER_WINDOW, WINDOW_MILLIS);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
        FixedWindowRateLimiter fixedWindowRateLimiter, ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(fixedWindowRateLimiter, objectMapper));
        registration.addUrlPatterns("/api/v1/*");
        registration.setName("rateLimitFilter");
        registration.setOrder(1);
        return registration;
    }
}
