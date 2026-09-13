package com.urlshortener.controller;

import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.PagedResponse;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.dto.UpdateShortUrlRequest;
import com.urlshortener.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every endpoint here requires ROLE_ADMIN (see SecurityConfig, matcher
 * /api/v1/urls/**) — creating, listing, reading metadata, updating,
 * deleting, and viewing stats are all management operations, unlike the
 * public redirect in RedirectController.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "URL Shortener", description = "Create, list, update, delete, and inspect short URLs (admin-only)")
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;

    public UrlShortenerController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @Operation(summary = "Create a short URL", description = "Creates a shortened URL for a valid absolute URL. Optionally accepts a future expiresAt timestamp after which the link stops redirecting. Requires ROLE_ADMIN.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Short URL created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid URL, custom code format, or payload"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Authenticated but not an admin"),
        @ApiResponse(responseCode = "409", description = "Short code already exists")
    })
    @PostMapping("/urls")
    public ResponseEntity<ShortUrlResponse> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlResponse response = urlShortenerService.createShortUrl(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List short URLs", description = "Returns a page of short URLs, newest first by default. Query params: page (0-based, default 0), size (default 20, max 100), sort (e.g. sort=clickCount,desc). Requires ROLE_ADMIN.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "A page of short URLs (possibly empty)"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Authenticated but not an admin")
    })
    @GetMapping("/urls")
    public ResponseEntity<PagedResponse<ShortUrlResponse>> listShortUrls(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(urlShortenerService.listShortUrls(pageable));
    }

    @Operation(summary = "Get short URL metadata", description = "Fetches details of a shortened URL by its code. Requires ROLE_ADMIN.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Short URL found"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Authenticated but not an admin"),
        @ApiResponse(responseCode = "404", description = "Short URL not found")
    })
    @GetMapping("/urls/{shortCode}")
    public ResponseEntity<ShortUrlResponse> getShortUrl(
        @Parameter(description = "Short code generated for the original URL", example = "abc12345")
        @PathVariable String shortCode) {
        return ResponseEntity.ok(urlShortenerService.getShortUrlByCode(shortCode));
    }

    @Operation(summary = "Update active status and/or expiration", description = "Partially updates a short URL's active flag and/or expiresAt. A null field is left unchanged (not cleared) — provide at least one of active/expiresAt. Note: an already-set expiresAt cannot be cleared back to null through this endpoint. Requires ROLE_ADMIN.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Updated successfully"),
        @ApiResponse(responseCode = "400", description = "No fields provided, or expiresAt not in the future"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Authenticated but not an admin"),
        @ApiResponse(responseCode = "404", description = "Short URL not found")
    })
    @PatchMapping("/urls/{shortCode}")
    public ResponseEntity<ShortUrlResponse> updateShortUrl(
        @Parameter(description = "Short code generated for the original URL", example = "abc12345")
        @PathVariable String shortCode,
        @Valid @RequestBody UpdateShortUrlRequest request) {
        return ResponseEntity.ok(urlShortenerService.updateShortUrl(shortCode, request));
    }

    @Operation(summary = "Delete a short URL", description = "Permanently removes a short URL and all of its click analytics. The short code stops redirecting immediately and becomes available for reuse. Requires ROLE_ADMIN.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Deleted"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Authenticated but not an admin"),
        @ApiResponse(responseCode = "404", description = "Short URL not found")
    })
    @DeleteMapping("/urls/{shortCode}")
    public ResponseEntity<Void> deleteShortUrl(
        @Parameter(description = "Short code generated for the original URL", example = "abc12345")
        @PathVariable String shortCode) {
        urlShortenerService.deleteShortUrl(shortCode);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get click analytics", description = "Returns click statistics for a shortened URL, including a daily click breakdown. Requires ROLE_ADMIN.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Stats found"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Authenticated but not an admin"),
        @ApiResponse(responseCode = "404", description = "Short URL not found")
    })
    @GetMapping("/urls/{shortCode}/stats")
    public ResponseEntity<ClickStatsResponse> getClickStats(
        @Parameter(description = "Short code generated for the original URL", example = "abc12345")
        @PathVariable String shortCode) {
        return ResponseEntity.ok(urlShortenerService.getClickStats(shortCode));
    }
}
