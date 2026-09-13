package com.urlshortener.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleInvalidUrl_shouldReturnBadRequest() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls");
        InvalidUrlException ex = new InvalidUrlException("Invalid URL format: example.com");

        ResponseEntity<ApiError> response = exceptionHandler.handleInvalidUrl(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid URL format: example.com", response.getBody().message());
        assertEquals("/api/v1/urls", response.getBody().path());
    }

    @Test
    void handleResourceNotFound_shouldReturnNotFound() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls/missing");
        ResourceNotFoundException ex = new ResourceNotFoundException("Short URL not found");

        ResponseEntity<ApiError> response = exceptionHandler.handleResourceNotFound(ex, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Short URL not found", response.getBody().message());
    }

    @Test
    void handleDuplicateCode_shouldReturnConflict() {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/urls");
        DuplicateShortCodeException ex = new DuplicateShortCodeException("Short code already exists");

        ResponseEntity<ApiError> response = exceptionHandler.handleDuplicateCode(ex, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Short code already exists", response.getBody().message());
    }

    @Test
    void handleUrlExpired_shouldReturnGone() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/abc12345");
        UrlExpiredException ex = new UrlExpiredException("Short URL has expired: abc12345");

        ResponseEntity<ApiError> response = exceptionHandler.handleUrlExpired(ex, request);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals("Short URL has expired: abc12345", response.getBody().message());
    }

    @Test
    void handleInvalidUpdateRequest_shouldReturnBadRequest() {
        HttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/urls/abc12345");
        InvalidUpdateRequestException ex = new InvalidUpdateRequestException("At least one of active or expiresAt must be provided");

        ResponseEntity<ApiError> response = exceptionHandler.handleInvalidUpdateRequest(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("At least one of active or expiresAt must be provided", response.getBody().message());
    }
}
