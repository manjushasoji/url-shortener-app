package com.urlshortener.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    /*
     * A plain `new ObjectMapper()` has no Java 8 date/time support, so it
     * fails serializing ApiError.timestamp (a LocalDateTime). In the running
     * app this is a non-issue: Spring Boot's autoconfigured ObjectMapper bean
     * already has jackson-datatype-jsr310 registered. findAndRegisterModules()
     * mirrors that here instead of hardcoding the JavaTimeModule import.
     */
    private static ObjectMapper newObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void doFilter_shouldPassThrough_whenUnderLimit() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 60_000);
        RateLimitFilter filter = new RateLimitFilter(limiter, newObjectMapper());
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
        RateLimitFilter filter = new RateLimitFilter(limiter, newObjectMapper());
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
    void doFilter_shouldRateLimitTheRootRedirect() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60_000);
        RateLimitFilter filter = new RateLimitFilter(limiter, newObjectMapper());
        FilterChain chain = Mockito.mock(FilterChain.class);

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/abc12345");
        first.setRemoteAddr("127.0.0.1");
        filter.doFilter(first, new MockHttpServletResponse(), chain);

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/abc12345");
        second.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, chain);

        assertEquals(429, secondResponse.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    /*
     * The filter is registered on /* (the redirect lives at the root now), so
     * the exemption list is what keeps Swagger UI's dozen asset loads and
     * actuator probes from eating the quota. Exempt requests must also not
     * consume a slot — the limiter is never even consulted for them.
     */
    @Test
    void shouldNotFilter_shouldExemptOperationalAndDocsPaths_withoutConsumingQuota() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60_000);
        RateLimitFilter filter = new RateLimitFilter(limiter, newObjectMapper());
        FilterChain chain = Mockito.mock(FilterChain.class);

        for (String path : new String[] {
            "/actuator/health", "/swagger-ui.html", "/swagger-ui/index.html",
            "/v3/api-docs", "/v3/api-docs/swagger-config", "/webjars/x.js", "/error"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRemoteAddr("127.0.0.1");
            assertTrue(filter.shouldNotFilter(request), path + " should be exempt");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }
        verify(chain, times(7)).doFilter(any(), any());

        // Quota untouched by the seven exempt requests: the first real one still passes.
        MockHttpServletRequest real = new MockHttpServletRequest("GET", "/abc12345");
        real.setRemoteAddr("127.0.0.1");
        assertFalse(filter.shouldNotFilter(real));
        MockHttpServletResponse realResponse = new MockHttpServletResponse();
        filter.doFilter(real, realResponse, chain);
        assertEquals(200, realResponse.getStatus());
    }

    @Test
    void doFilter_shouldTrackDifferentIpsIndependently() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60_000);
        RateLimitFilter filter = new RateLimitFilter(limiter, newObjectMapper());
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
