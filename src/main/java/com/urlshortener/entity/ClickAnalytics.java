package com.urlshortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "click_analytics",
    indexes = {
        @Index(name = "idx_click_analytics_short_url_id", columnList = "short_url_id"),
        @Index(name = "idx_click_analytics_clicked_at", columnList = "clicked_at")
    }
)
public class ClickAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_url_id", nullable = false)
    private Long shortUrlId;

    @Column(name = "clicked_at", nullable = false)
    private LocalDateTime clickedAt;

    @Column(name = "referrer", length = 2048)
    private String referrer;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    protected ClickAnalytics() {
    }

    public ClickAnalytics(Long shortUrlId, String referrer, String userAgent) {
        this.shortUrlId = shortUrlId;
        this.referrer = referrer;
        this.userAgent = userAgent;
    }

    @PrePersist
    protected void onCreate() {
        this.clickedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getShortUrlId() {
        return shortUrlId;
    }

    public LocalDateTime getClickedAt() {
        return clickedAt;
    }

    public String getReferrer() {
        return referrer;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
