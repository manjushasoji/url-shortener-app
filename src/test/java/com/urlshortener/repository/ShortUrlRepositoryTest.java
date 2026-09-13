package com.urlshortener.repository;

import com.urlshortener.entity.ShortUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortUrlRepositoryTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;

    @Test
    void findByShortCode_shouldReturnMatchingShortUrl() {
        ShortUrl shortUrl = new ShortUrl("abc12345", "https://example.com");
        when(shortUrlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(shortUrl));

        Optional<ShortUrl> result = shortUrlRepository.findByShortCode("abc12345");

        assertTrue(result.isPresent());
        assertEquals("https://example.com", result.get().getOriginalUrl());
    }

    @Test
    void existsByShortCode_shouldReturnTrueForExistingCode() {
        when(shortUrlRepository.existsByShortCode("code999")).thenReturn(true);

        boolean exists = shortUrlRepository.existsByShortCode("code999");

        assertTrue(exists);
        verify(shortUrlRepository).existsByShortCode("code999");
    }
}
