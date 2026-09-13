package com.urlshortener.service;

import com.urlshortener.dto.ClickStatsResponse;
import com.urlshortener.dto.CreateShortUrlRequest;
import com.urlshortener.dto.PagedResponse;
import com.urlshortener.dto.ShortUrlResponse;
import com.urlshortener.dto.UpdateShortUrlRequest;
import org.springframework.data.domain.Pageable;

public interface UrlShortenerService {

    ShortUrlResponse createShortUrl(CreateShortUrlRequest request);

    ShortUrlResponse getShortUrlByCode(String shortCode);

    PagedResponse<ShortUrlResponse> listShortUrls(Pageable pageable);

    void deleteShortUrl(String shortCode);

    ShortUrlResponse updateShortUrl(String shortCode, UpdateShortUrlRequest request);

    String redirectToOriginalUrl(String shortCode, String referrer, String userAgent);

    ClickStatsResponse getClickStats(String shortCode);
}
