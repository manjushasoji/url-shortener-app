package com.urlshortener.repository;

import com.urlshortener.entity.ClickAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClickAnalyticsRepository extends JpaRepository<ClickAnalytics, Long> {

    long countByShortUrlId(Long shortUrlId);

    @Query("SELECT MIN(c.clickedAt) FROM ClickAnalytics c WHERE c.shortUrlId = :shortUrlId")
    Optional<LocalDateTime> findFirstClickAt(@Param("shortUrlId") Long shortUrlId);

    @Query("SELECT MAX(c.clickedAt) FROM ClickAnalytics c WHERE c.shortUrlId = :shortUrlId")
    Optional<LocalDateTime> findLastClickAt(@Param("shortUrlId") Long shortUrlId);

    @Query("SELECT CAST(c.clickedAt AS date) AS clickDate, COUNT(c) AS clickCount "
        + "FROM ClickAnalytics c WHERE c.shortUrlId = :shortUrlId "
        + "GROUP BY CAST(c.clickedAt AS date) ORDER BY clickDate ASC")
    List<DailyClickCountProjection> findDailyClickCounts(@Param("shortUrlId") Long shortUrlId);

    interface DailyClickCountProjection {
        LocalDate getClickDate();
        long getClickCount();
    }
}
