package com.urlshortener.controller;

import com.urlshortener.config.SecurityConfig;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No @WithMockUser anywhere in this class, deliberately: SecurityConfig
 * marks GET /api/v1/{shortCode} as permitAll(), so these requests going
 * through unauthenticated (and succeeding) is itself the proof that the
 * redirect endpoint really is public. If that matcher were ever wrong,
 * these would fail with 401, not the assertion they're actually checking.
 */
@WebMvcTest(RedirectController.class)
@Import(SecurityConfig.class)
class RedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlShortenerService urlShortenerService;

    @Test
    void redirect_shouldReturnNonCacheableRedirect_withoutAuthentication() throws Exception {
        when(urlShortenerService.redirectToOriginalUrl(eq("xyz987"), any(), any()))
            .thenReturn("https://example.org");

        mockMvc.perform(get("/api/v1/{shortCode}", "xyz987")
                .header("Referer", "https://ref.example")
                .header("User-Agent", "test-agent"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://example.org"));

        verify(urlShortenerService).redirectToOriginalUrl("xyz987", "https://ref.example", "test-agent");
    }

    @Test
    void redirect_shouldReturnNotFound_whenCodeDoesNotExist_withoutAuthentication() throws Exception {
        when(urlShortenerService.redirectToOriginalUrl(eq("missing"), any(), any()))
            .thenThrow(new ResourceNotFoundException("Short URL not found for code: missing"));

        mockMvc.perform(get("/api/v1/{shortCode}", "missing"))
            .andExpect(status().isNotFound());
    }
}
