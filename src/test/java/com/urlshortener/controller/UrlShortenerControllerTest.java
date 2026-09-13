package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.CreateShortUrlRequest;
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

import java.time.LocalDateTime;
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
        when(urlShortenerService.redirectToOriginalUrl("xyz987")).thenReturn("https://example.org");

        RedirectView view = controller.redirect("xyz987");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        view.render(Map.of(), request, response);

        assertEquals("https://example.org", response.getHeader("Location"));
        assertEquals(HttpServletResponse.SC_MOVED_PERMANENTLY, response.getStatus());
    }
}
