package com.urlshortener.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Replaces Spring Boot's default /error handling (the whitelabel HTML page,
 * or its own differently-shaped JSON body) with this project's ApiError
 * format, for anything that never reaches a controller method at all — most
 * notably a request to a path that matches none of this API's routes.
 * GlobalExceptionHandler only catches exceptions thrown from within a
 * controller method; a genuinely unmapped path never gets that far, and is
 * instead forwarded by the servlet container to /error, which this bean now
 * owns (Spring Boot's own ErrorController backs off once one is defined).
 *
 * Deliberately requests only Include.MESSAGE from ErrorAttributes, not
 * EXCEPTION or STACK_TRACE, so this never leaks internal exception class
 * names or stack traces in the response.
 */
@RestController
public class ApiErrorController implements ErrorController {

    private final ErrorAttributes errorAttributes;

    public ApiErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("/error")
    public ResponseEntity<ApiError> handleError(HttpServletRequest request) {
        WebRequest webRequest = new ServletWebRequest(request);
        Map<String, Object> attributes = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.of(ErrorAttributeOptions.Include.MESSAGE));

        HttpStatus status = HttpStatus.valueOf(((Number) attributes.getOrDefault("status", 500)).intValue());
        String path = String.valueOf(attributes.getOrDefault("path", request.getRequestURI()));
        String message = String.valueOf(attributes.getOrDefault("message", status.getReasonPhrase()));

        ApiError apiError = new ApiError(LocalDateTime.now(), status.value(), status.getReasonPhrase(), message, path);
        return ResponseEntity.status(status).body(apiError);
    }
}
