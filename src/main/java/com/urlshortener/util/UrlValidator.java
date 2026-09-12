package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;

import java.net.URI;
import java.net.URISyntaxException;

public final class UrlValidator {

    private UrlValidator() {
    }

    public static String normalizeAndValidate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidUrlException("URL is required");
        }

        String normalized = rawUrl.trim();
        if (!isValidUrl(normalized)) {
            throw new InvalidUrlException("Invalid URL format: " + normalized);
        }

        return normalized;
    }

    public static boolean isValidUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }

        try {
            URI uri = new URI(rawUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme == null || host == null) {
                return false;
            }

            return (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
