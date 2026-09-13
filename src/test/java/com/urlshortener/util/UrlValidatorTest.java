package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlValidatorTest {

    @Test
    void normalizeAndValidate_shouldReturnTrimmedUrl_forValidHttpUrl() {
        String url = "  http://example.com  ";

        String result = UrlValidator.normalizeAndValidate(url);

        assertEquals("http://example.com", result);
    }

    @Test
    void normalizeAndValidate_shouldReturnTrimmedUrl_forValidHttpsUrl() {
        String url = "https://www.example.com/path?name=value";

        String result = UrlValidator.normalizeAndValidate(url);

        assertEquals(url, result);
    }

    @Test
    void normalizeAndValidate_shouldThrowInvalidUrlException_whenUrlIsNull() {
        InvalidUrlException exception = assertThrows(
            InvalidUrlException.class,
            () -> UrlValidator.normalizeAndValidate(null)
        );

        assertEquals("URL is required", exception.getMessage());
    }

    @Test
    void normalizeAndValidate_shouldThrowInvalidUrlException_whenUrlIsBlank() {
        InvalidUrlException exception = assertThrows(
            InvalidUrlException.class,
            () -> UrlValidator.normalizeAndValidate("   ")
        );

        assertEquals("URL is required", exception.getMessage());
    }

    @Test
    void normalizeAndValidate_shouldThrowInvalidUrlException_whenUrlHasInvalidFormat() {
        InvalidUrlException exception = assertThrows(
            InvalidUrlException.class,
            () -> UrlValidator.normalizeAndValidate("example.com")
        );

        assertTrue(exception.getMessage().startsWith("Invalid URL format: "));
        assertEquals("Invalid URL format: example.com", exception.getMessage());
    }

    @Test
    void isValidUrl_shouldReturnTrue_forValidHttpAndHttpsUrls() {
        assertTrue(UrlValidator.isValidUrl("http://localhost:8080"));
        assertTrue(UrlValidator.isValidUrl("https://www.example.com"));
    }

    @Test
    void isValidUrl_shouldReturnFalse_forNullBlankAndUnsupportedSchemes() {
        assertFalse(UrlValidator.isValidUrl(null));
        assertFalse(UrlValidator.isValidUrl("   "));
        assertFalse(UrlValidator.isValidUrl("ftp://example.com"));
        assertFalse(UrlValidator.isValidUrl("example.com"));
    }
}
