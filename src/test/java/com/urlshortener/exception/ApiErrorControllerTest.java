package com.urlshortener.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The status this controller reports comes from HttpServletResponse.getStatus(),
 * not from the "status" key in the (mocked) error-attributes map — see the
 * comment in ApiErrorController for why. Each test below sets the response
 * status explicitly, the way the servlet container would via sendError()
 * before forwarding to /error, rather than relying on the attributes map.
 */
@ExtendWith(MockitoExtension.class)
class ApiErrorControllerTest {

    @Mock
    private ErrorAttributes errorAttributes;

    @Test
    void handleError_shouldReturnStructuredApiError_forUnmappedPath() {
        when(errorAttributes.getErrorAttributes(any(), any(ErrorAttributeOptions.class)))
            .thenReturn(Map.of(
                "path", "/api/v1/does-not-exist/at/all",
                "message", "No static resource api/v1/does-not-exist/at/all."
            ));

        ApiErrorController controller = new ApiErrorController(errorAttributes);
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/does-not-exist/at/all");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpStatus.NOT_FOUND.value());

        ResponseEntity<ApiError> result = controller.handleError(request, response);

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
        assertEquals(404, result.getBody().status());
        assertEquals("/api/v1/does-not-exist/at/all", result.getBody().path());
        assertEquals("Not Found", result.getBody().error());
        assertEquals("No static resource api/v1/does-not-exist/at/all.", result.getBody().message());
    }

    @Test
    void handleError_shouldReturnUnauthorized_whenSecurityRejectsBeforeAnyControllerRuns() {
        // Reproduces the real bug: a security-triggered 401 (sendError, not a
        // thrown exception) with a "message" attribute but no "status" attribute
        // previously silently defaulted to 500.
        when(errorAttributes.getErrorAttributes(any(), any(ErrorAttributeOptions.class)))
            .thenReturn(Map.of("path", "/api/v1/urls/abc12345", "message", "Unauthorized"));

        ApiErrorController controller = new ApiErrorController(errorAttributes);
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls/abc12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());

        ResponseEntity<ApiError> result = controller.handleError(request, response);

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
        assertEquals(401, result.getBody().status());
        assertEquals("Unauthorized", result.getBody().message());
    }

    @Test
    void handleError_shouldFallBackToInternalServerError_whenResponseStatusUnset() {
        when(errorAttributes.getErrorAttributes(any(), any(ErrorAttributeOptions.class)))
            .thenReturn(Map.of("path", "/api/v1/boom"));

        ApiErrorController controller = new ApiErrorController(errorAttributes);
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/boom");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // MockHttpServletResponse defaults to 200; simulate the "nothing set the
        // status" edge case explicitly rather than relying on that default.
        response.setStatus(0);

        ResponseEntity<ApiError> result = controller.handleError(request, response);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals("Internal Server Error", result.getBody().message());
    }

    @Test
    void handleError_shouldFallBackToRequestUri_whenPathAttributeMissing() {
        when(errorAttributes.getErrorAttributes(any(), any(ErrorAttributeOptions.class)))
            .thenReturn(Map.of());

        ApiErrorController controller = new ApiErrorController(errorAttributes);
        HttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/urls/abc12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());

        ResponseEntity<ApiError> result = controller.handleError(request, response);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, result.getStatusCode());
        assertEquals("/api/v1/urls/abc12345", result.getBody().path());
    }
}
