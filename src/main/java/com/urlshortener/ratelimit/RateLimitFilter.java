package com.urlshortener.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Per-client-IP rate limiting for the public API. Uses request.getRemoteAddr()
 * as the client key, which is the immediate TCP peer — behind a reverse proxy
 * or load balancer without X-Forwarded-For handling, every request would
 * appear to come from the proxy's IP. Not handled here; see README.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final FixedWindowRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(FixedWindowRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String clientKey = request.getRemoteAddr();

        if (!rateLimiter.tryConsume(clientKey)) {
            writeTooManyRequests(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiError apiError = new ApiError(
            LocalDateTime.now(),
            HttpStatus.TOO_MANY_REQUESTS.value(),
            HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
            "Rate limit exceeded. Please try again later.",
            request.getRequestURI()
        );

        response.getWriter().write(objectMapper.writeValueAsString(apiError));
    }
}
