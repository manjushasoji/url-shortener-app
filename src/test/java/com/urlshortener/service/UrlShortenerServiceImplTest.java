package com.urlshortener.service;

import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.InvalidUrlException;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceImplTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;

    @InjectMocks
    private UrlShortenerServiceImpl urlShortenerService;

    @Test
    void createShortUrl_shouldReturnResponse_whenUrlIsValid() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "abc12345");
        ShortUrl savedEntity = new ShortUrl("abc12345", "https://example.com");

        when(shortUrlRepository.existsByShortCode("abc12345")).thenReturn(false);
        when(shortUrlRepository.save(any(ShortUrl.class))).thenReturn(savedEntity);

        ShortUrlResponse response = urlShortenerService.createShortUrl(request);

        assertEquals("abc12345", response.shortCode());
        assertEquals("https://example.com", response.originalUrl());
        assertEquals(Boolean.TRUE, response.active());
    }

    @Test
    void createShortUrl_shouldThrowInvalidUrlException_whenOriginalUrlIsInvalid() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("example.com", null);

        assertThrows(InvalidUrlException.class, () -> urlShortenerService.createShortUrl(request));
    }

    @Test
    void getShortUrlByCode_shouldThrowResourceNotFound_whenCodeDoesNotExist() {
        when(shortUrlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> urlShortenerService.getShortUrlByCode("missing"));
    }

    @Test
    void redirectToOriginalUrl_shouldReturnOriginalUrlAndIncrementClickCount() {
        ShortUrl existing = new ShortUrl("abc12345", "https://example.com");
        existing.setClickCount(1L);

        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(existing));
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String originalUrl = urlShortenerService.redirectToOriginalUrl("abc12345");

        assertEquals("https://example.com", originalUrl);
        assertEquals(2L, existing.getClickCount());
    }

    @Test
    void normalizeCustomCode_shouldThrowDuplicateShortCodeException_whenCodeIsInvalid() {
        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "@#$");

        assertThrows(DuplicateShortCodeException.class, () -> urlShortenerService.createShortUrl(request));
    }
}
