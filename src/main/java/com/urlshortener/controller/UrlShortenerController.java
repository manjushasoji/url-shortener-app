package com.urlshortener.controller;

import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "URL Shortener", description = "Create and resolve short URLs")
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;

    public UrlShortenerController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @Operation(summary = "Create a short URL", description = "Creates a shortened URL for a valid absolute URL. Optionally accepts a future expiresAt timestamp after which the link stops redirecting.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Short URL created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid URL or payload"),
        @ApiResponse(responseCode = "409", description = "Short code already exists")
    })
    @PostMapping("/urls")
    public ResponseEntity<ShortUrlResponse> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlResponse response = urlShortenerService.createShortUrl(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get short URL metadata", description = "Fetches details of a shortened URL by its code.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Short URL found"),
        @ApiResponse(responseCode = "404", description = "Short URL not found")
    })
    @GetMapping("/urls/{shortCode}")
    public ResponseEntity<ShortUrlResponse> getShortUrl(
        @Parameter(description = "Short code generated for the original URL", example = "abc12345")
        @PathVariable String shortCode) {
        return ResponseEntity.ok(urlShortenerService.getShortUrlByCode(shortCode));
    }

    @Operation(summary = "Redirect to original URL", description = "Redirects users to the original URL using the short code. Uses a 302 (not 301) so browsers re-request the redirect on every click instead of caching it, which would otherwise cause click counts to be undercounted.")
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

    @Operation(summary = "Get click analytics", description = "Returns click statistics for a shortened URL, including a daily click breakdown.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Stats found"),
        @ApiResponse(responseCode = "404", description = "Short URL not found")
    })
    @GetMapping("/urls/{shortCode}/stats")
    public ResponseEntity<ClickStatsResponse> getClickStats(
        @Parameter(description = "Short code generated for the original URL", example = "abc12345")
        @PathVariable String shortCode) {
        return ResponseEntity.ok(urlShortenerService.getClickStats(shortCode));
    }
}
