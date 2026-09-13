package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.config.SecurityConfig;
import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.DailyClickCount;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.dto.UpdateShortUrlRequest;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WithMockUser(roles = "ADMIN") at the class level authenticates every test
 * as an admin by default, matching what every endpoint here requires
 * (SecurityConfig gates /api/v1/urls/** behind ROLE_ADMIN). The two
 * unauthenticated/unauthorized tests below override that per-method.
 */
@WebMvcTest(UrlShortenerController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class UrlShortenerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UrlShortenerService urlShortenerService;

    @Test
    void createShortUrl_shouldReturnCreatedResponse() throws Exception {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "abc12345");
        ShortUrlResponse response = new ShortUrlResponse(1L, "abc12345", "https://example.com", 0L, true, LocalDateTime.now());

        when(urlShortenerService.createShortUrl(any(CreateShortUrlRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.shortCode").value("abc12345"))
            .andExpect(jsonPath("$.originalUrl").value("https://example.com"));

        verify(urlShortenerService).createShortUrl(any(CreateShortUrlRequest.class));
    }

    @Test
    @WithAnonymousUser
    void createShortUrl_shouldReturnUnauthorized_whenNotAuthenticated() throws Exception {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "abc12345");

        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void createShortUrl_shouldReturnForbidden_whenAuthenticatedWithoutAdminRole() throws Exception {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "abc12345");

        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden());
    }

    @Test
    void getShortUrl_shouldReturnMetaDetails() throws Exception {
        ShortUrlResponse response = new ShortUrlResponse(2L, "xyz987", "https://example.org", 4L, true, LocalDateTime.now());

        when(urlShortenerService.getShortUrlByCode("xyz987")).thenReturn(response);

        mockMvc.perform(get("/api/v1/urls/{shortCode}", "xyz987"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shortCode").value("xyz987"))
            .andExpect(jsonPath("$.clickCount").value(4));
    }

    @Test
    void updateShortUrl_shouldReturnUpdatedResponse() throws Exception {
        LocalDateTime newExpiresAt = LocalDateTime.now().plusDays(2);
        UpdateShortUrlRequest request = new UpdateShortUrlRequest(false, newExpiresAt);
        ShortUrlResponse response = new ShortUrlResponse(1L, "abc12345", "https://example.com", 0L, false, LocalDateTime.now(), newExpiresAt);

        when(urlShortenerService.updateShortUrl(eq("abc12345"), any(UpdateShortUrlRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/v1/urls/{shortCode}", "abc12345")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));

        verify(urlShortenerService).updateShortUrl(eq("abc12345"), any(UpdateShortUrlRequest.class));
    }

    @Test
    void updateShortUrl_shouldReturnBadRequest_whenExpiresAtIsInThePast() throws Exception {
        UpdateShortUrlRequest request = new UpdateShortUrlRequest(null, LocalDateTime.now().minusDays(1));

        mockMvc.perform(patch("/api/v1/urls/{shortCode}", "abc12345")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateShortUrl_shouldReturnNotFound_whenCodeDoesNotExist() throws Exception {
        UpdateShortUrlRequest request = new UpdateShortUrlRequest(false, null);

        when(urlShortenerService.updateShortUrl(eq("missing"), any(UpdateShortUrlRequest.class)))
            .thenThrow(new ResourceNotFoundException("Short URL not found for code: missing"));

        mockMvc.perform(patch("/api/v1/urls/{shortCode}", "missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound());
    }

    @Test
    void getClickStats_shouldReturnAggregatedStats() throws Exception {
        ClickStatsResponse stats = new ClickStatsResponse(
            "xyz987",
            9L,
            LocalDateTime.of(2026, 1, 1, 9, 0),
            LocalDateTime.of(2026, 1, 2, 15, 30),
            List.of(new DailyClickCount(LocalDate.of(2026, 1, 1), 4), new DailyClickCount(LocalDate.of(2026, 1, 2), 5))
        );

        when(urlShortenerService.getClickStats("xyz987")).thenReturn(stats);

        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", "xyz987"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shortCode").value("xyz987"))
            .andExpect(jsonPath("$.totalClicks").value(9))
            .andExpect(jsonPath("$.dailyBreakdown.length()").value(2))
            .andExpect(jsonPath("$.dailyBreakdown[0].count").value(4));
    }
}
