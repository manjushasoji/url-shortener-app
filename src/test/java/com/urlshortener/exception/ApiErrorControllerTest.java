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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiErrorControllerTest {

    @Mock
    private ErrorAttributes errorAttributes;

    @Test
    void handleError_shouldReturnStructuredApiError_forUnmappedPath() {
        when(errorAttributes.getErrorAttributes(any(), any(ErrorAttributeOptions.class)))
            .thenReturn(Map.of(
                "status", 404,
                "path", "/api/v1/does-not-exist/at/all",
                "message", "No static resource api/v1/does-not-exist/at/all."
            ));

        ApiErrorController controller = new ApiErrorController(errorAttributes);
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/does-not-exist/at/all");

        ResponseEntity<ApiError> response = controller.handleError(request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().status());
        assertEquals("/api/v1/does-not-exist/at/all", response.getBody().path());
        assertEquals("Not Found", response.getBody().error());
    }

    @Test
    void handleError_shouldFallBackToInternalServerError_whenStatusAttributeMissing() {
        when(errorAttributes.getErrorAttributes(any(), any(ErrorAttributeOptions.class)))
            .thenReturn(Map.of("path", "/api/v1/boom"));

        ApiErrorController controller = new ApiErrorController(errorAttributes);
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/boom");

        ResponseEntity<ApiError> response = controller.handleError(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Internal Server Error", response.getBody().message());
    }

    @Test
    void handleError_shouldFallBackToRequestUri_whenPathAttributeMissing() {
        when(errorAttributes.getErrorAttributes(any(), any(ErrorAttributeOptions.class)))
            .thenReturn(Map.of("status", 405));

        ApiErrorController controller = new ApiErrorController(errorAttributes);
        HttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/urls/abc12345");

        ResponseEntity<ApiError> response = controller.handleError(request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("/api/v1/urls/abc12345", response.getBody().path());
    }
}
