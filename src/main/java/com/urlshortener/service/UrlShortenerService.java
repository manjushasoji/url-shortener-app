package com.urlshortener.service;

import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.ShortUrlResponse;

public interface UrlShortenerService {

    ShortUrlResponse createShortUrl(CreateShortUrlRequest request);

    ShortUrlResponse getShortUrlByCode(String shortCode);

    String redirectToOriginalUrl(String shortCode);
}
