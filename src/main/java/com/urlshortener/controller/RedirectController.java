package com.urlshortener.controller;

import com.urlshortener.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Kept separate from UrlShortenerController specifically so it can stay
 * public while every other /api/v1 endpoint requires ROLE_ADMIN (see
 * SecurityConfig) — a URL shortener's redirect has to work for anonymous
 * visitors, unlike creating/managing links.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Redirect", description = "Publicly resolve a short code to its original URL")
public class RedirectController {

    private final UrlShortenerService urlShortenerService;

    public RedirectController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @Operation(summary = "Redirect to original URL", description = "Redirects users to the original URL using the short code. Uses a 302 (not 301) so browsers re-request the redirect on every click instead of caching it, which would otherwise cause click counts to be undercounted. Publicly accessible — no authentication required.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "302", description = "Redirected successfully"),
        @ApiResponse(responseCode = "404", description = "Short URL not found or inactive"),
        @ApiResponse(responseCode = "410", description = "Short URL has expired")
    })
    @GetMapping("/{shortCode}")
    public RedirectView redirect(
        @Parameter(description = "Short code generated for the original URL", example = "abc12345")
        @PathVariable String shortCode,
        HttpServletRequest request) {
        String originalUrl = urlShortenerService.redirectToOriginalUrl(
            shortCode,
            request.getHeader("Referer"),
            request.getHeader("User-Agent")
        );
        RedirectView redirectView = new RedirectView(originalUrl);
        redirectView.setStatusCode(HttpStatus.FOUND);
        return redirectView;
    }
}
