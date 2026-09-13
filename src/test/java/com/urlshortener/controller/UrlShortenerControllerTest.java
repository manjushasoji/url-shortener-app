package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.config.SecurityConfig;
import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.DailyClickCount;
import com.urlshortener.dto.PagedResponse;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.dto.UpdateShortUrlRequest;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    void listShortUrls_shouldReturnPage_withNewestFirstDefault() throws Exception {
        ShortUrlResponse newer = new ShortUrlResponse(2L, "newer123", "https://b.example", 5L, true, LocalDateTime.now());
        ShortUrlResponse older = new ShortUrlResponse(1L, "older123", "https://a.example", 1L, true, LocalDateTime.now().minusDays(1));
        when(urlShortenerService.listShortUrls(any(Pageable.class)))
            .thenReturn(new PagedResponse<>(List.of(newer, older), 0, 20, 2, 1));

        mockMvc.perform(get("/api/v1/urls"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].shortCode").value("newer123"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(1));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(urlShortenerService).listShortUrls(pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"), pageable.getValue().getSort());
    }

    @Test
    void listShortUrls_shouldHonourPageSizeAndSortParams() throws Exception {
        when(urlShortenerService.listShortUrls(any(Pageable.class)))
            .thenReturn(new PagedResponse<>(List.of(), 3, 5, 0, 0));

        mockMvc.perform(get("/api/v1/urls").param("page", "3").param("size", "5").param("sort", "clickCount,desc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(urlShortenerService).listShortUrls(pageable.capture());
        assertEquals(3, pageable.getValue().getPageNumber());
        assertEquals(5, pageable.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "clickCount"), pageable.getValue().getSort());
    }

    /*
     * /api/v1/urls has no trailing segment, so this is the one endpoint whose
     * coverage by the /api/v1/urls/** matcher depends on ** matching zero
     * segments. A 401 here is the proof that it does.
     */
    @Test
    @WithAnonymousUser
    void listShortUrls_shouldReturnUnauthorized_whenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/urls"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteShortUrl_shouldReturnNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/{shortCode}", "abc12345"))
            .andExpect(status().isNoContent());

        verify(urlShortenerService).deleteShortUrl("abc12345");
    }

    @Test
    void deleteShortUrl_shouldReturnNotFound_whenCodeDoesNotExist() throws Exception {
        doThrow(new ResourceNotFoundException("Short URL not found for code: missing"))
            .when(urlShortenerService).deleteShortUrl("missing");

        mockMvc.perform(delete("/api/v1/urls/{shortCode}", "missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Short URL not found for code: missing"));
    }

    @Test
    @WithAnonymousUser
    void deleteShortUrl_shouldReturnUnauthorized_whenNotAuthenticated() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/{shortCode}", "abc12345"))
            .andExpect(status().isUnauthorized());
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
