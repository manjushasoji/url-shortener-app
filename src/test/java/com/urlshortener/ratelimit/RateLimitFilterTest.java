package com.urlshortener.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    @Test
    void doFilter_shouldPassThrough_whenUnderLimit() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 60_000);
        RateLimitFilter filter = new RateLimitFilter(limiter, new ObjectMapper());
        FilterChain chain = Mockito.mock(FilterChain.class);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls/abc12345");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void doFilter_shouldReturn429_whenLimitExceeded() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60_000);
        RateLimitFilter filter = new RateLimitFilter(limiter, new ObjectMapper());
        FilterChain chain = Mockito.mock(FilterChain.class);

        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/api/v1/urls/abc12345");
        firstRequest.setRemoteAddr("127.0.0.1");
        filter.doFilter(firstRequest, new MockHttpServletResponse(), chain);

        MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", "/api/v1/urls/abc12345");
        secondRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(secondRequest, secondResponse, chain);

        assertEquals(429, secondResponse.getStatus());
        assertEquals("application/json", secondResponse.getContentType());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void doFilter_shouldTrackDifferentIpsIndependently() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60_000);
        RateLimitFilter filter = new RateLimitFilter(limiter, new ObjectMapper());
        FilterChain chain = Mockito.mock(FilterChain.class);

        MockHttpServletRequest requestFromIpA = new MockHttpServletRequest("GET", "/api/v1/urls/abc12345");
        requestFromIpA.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse responseA = new MockHttpServletResponse();
        filter.doFilter(requestFromIpA, responseA, chain);

        MockHttpServletRequest requestFromIpB = new MockHttpServletRequest("GET", "/api/v1/urls/abc12345");
        requestFromIpB.setRemoteAddr("127.0.0.2");
        MockHttpServletResponse responseB = new MockHttpServletResponse();
        filter.doFilter(requestFromIpB, responseB, chain);

        assertEquals(200, responseA.getStatus());
        assertEquals(200, responseB.getStatus());
        verify(chain, times(2)).doFilter(any(), any());
    }
}
