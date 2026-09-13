package com.urlshortener.repository;

import com.urlshortener.entity.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * A direct atomic UPDATE rather than load-increment-save: avoids a
     * read-modify-write race between two concurrent redirects on the same
     * short code (the previous entity-based approach had no @Version field
     * guarding it), and keeps the redirect's hot path from needing to load
     * the full entity at all when the cached lookup already has everything
     * else it needs — see ShortUrlCache.
     */
    @Modifying
    @Query("UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1 WHERE s.id = :id")
    void incrementClickCount(@Param("id") Long id);
}
