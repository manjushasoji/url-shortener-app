package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.DailyClickCount;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.view.RedirectView;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlShortenerController.class)
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
    void getShortUrl_shouldReturnMetaDetails() throws Exception {
        ShortUrlResponse response = new ShortUrlResponse(2L, "xyz987", "https://example.org", 4L, true, LocalDateTime.now());

        when(urlShortenerService.getShortUrlByCode("xyz987")).thenReturn(response);

        mockMvc.perform(get("/api/v1/urls/{shortCode}", "xyz987"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shortCode").value("xyz987"))
            .andExpect(jsonPath("$.clickCount").value(4));
    }

    @Test
    void redirect_shouldReturnPermanentRedirect() throws Exception {
        UrlShortenerController controller = new UrlShortenerController(urlShortenerService);
        MockHttpServletRequest incomingRequest = new MockHttpServletRequest();
        incomingRequest.addHeader("Referer", "https://ref.example");
        incomingRequest.addHeader("User-Agent", "test-agent");

        when(urlShortenerService.redirectToOriginalUrl("xyz987", "https://ref.example", "test-agent"))
            .thenReturn("https://example.org");

        RedirectView view = controller.redirect("xyz987", incomingRequest);
        MockHttpServletResponse response = new MockHttpServletResponse();

        view.render(Map.of(), incomingRequest, response);

        assertEquals("https://example.org", response.getHeader("Location"));
        assertEquals(HttpServletResponse.SC_MOVED_PERMANENTLY, response.getStatus());
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
