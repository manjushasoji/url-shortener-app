package com.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAgentParserTest {

    @Test
    void extractBrowserName_shouldReturnUnknown_whenUserAgentIsNullOrBlank() {
        assertEquals("Unknown", UserAgentParser.extractBrowserName(null));
        assertEquals("Unknown", UserAgentParser.extractBrowserName("  "));
    }

    @Test
    void extractBrowserName_shouldDetectChrome() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        assertEquals("Chrome", UserAgentParser.extractBrowserName(ua));
    }

    @Test
    void extractBrowserName_shouldDetectFirefox() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0";
        assertEquals("Firefox", UserAgentParser.extractBrowserName(ua));
    }

    @Test
    void extractBrowserName_shouldDetectSafari() {
        String ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";
        assertEquals("Safari", UserAgentParser.extractBrowserName(ua));
    }

    @Test
    void extractBrowserName_shouldDetectEdge_evenThoughItContainsChromeAndSafariTokens() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";
        assertEquals("Edge", UserAgentParser.extractBrowserName(ua));
    }

    @Test
    void extractBrowserName_shouldDetectOpera_evenThoughItContainsChromeToken() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 OPR/106.0.0.0";
        assertEquals("Opera", UserAgentParser.extractBrowserName(ua));
    }

    @Test
    void extractBrowserName_shouldReturnOther_forUnrecognizedClient() {
        assertEquals("Other", UserAgentParser.extractBrowserName("curl/8.4.0"));
    }
}
