package com.urlshortener.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ApiError> handleInvalidUrl(InvalidUrlException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(DuplicateShortCodeException.class)
    public ResponseEntity<ApiError> handleDuplicateCode(DuplicateShortCodeException ex, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(UrlExpiredException.class)
    public ResponseEntity<ApiError> handleUrlExpired(UrlExpiredException ex, HttpServletRequest request) {
        return buildError(HttpStatus.GONE, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidUpdateRequestException.class)
    public ResponseEntity<ApiError> handleInvalidUpdateRequest(InvalidUpdateRequestException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getDefaultMessage())
            .orElse("Validation failed");

        return buildError(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request.getRequestURI());
    }

    private ResponseEntity<ApiError> buildError(HttpStatus status, String message, String path) {
        ApiError error = new ApiError(
            LocalDateTime.now(),
            status.value(),
            status.getReasonPhrase(),
            message,
            path
        );
        return ResponseEntity.status(status).body(error);
    }
}
