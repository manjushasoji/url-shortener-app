package com.urlshortener.util;

import java.util.Locale;

public final class UserAgentParser {

    private UserAgentParser() {
    }

    /**
     * Reduces a raw User-Agent header down to a single browser name.
     * Checks the most specific/derived browsers first, since Edge, Opera, and
     * Chrome all include other browsers' tokens (e.g. "Safari", "Chrome") in
     * their own User-Agent strings for legacy compatibility.
     */
    public static String extractBrowserName(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }

        String ua = userAgent.toLowerCase(Locale.ROOT);

        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return "Firefox";
        }
        if (ua.contains("safari/") && ua.contains("version/")) {
            return "Safari";
        }
        if (ua.contains("msie") || ua.contains("trident/")) {
            return "Internet Explorer";
        }

        return "Other";
    }
}
