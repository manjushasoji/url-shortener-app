# Architecture Overview

## 1. Components

The service is a single Spring Boot application in three layers:

```
Client
  │  HTTP (JSON / redirect)
  ▼
┌───────────────────────────────────────────────────────────┐
│ UrlShortenerController                                     │
│  - POST /api/v1/urls               (create)                │
│  - GET  /api/v1/urls/{code}        (metadata)               │
│  - GET  /api/v1/urls/{code}/stats  (click analytics)         │
│  - GET  /api/v1/{code}             (redirect)                │
│  - request validation (Bean Validation on DTOs)            │
│  - extracts Referer / User-Agent headers for analytics      │
│  - delegates all business logic to the service layer       │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│ UrlShortenerService (interface) / UrlShortenerServiceImpl   │
│  - normalizes + validates the submitted URL (UrlValidator)  │
│  - generates an 8-char random short code, or normalizes a   │
│    caller-supplied custom code                              │
│  - enforces short-code uniqueness before persisting          │
│  - increments click_count and records a click_analytics      │
│    row on redirect                                           │
│  - aggregates click_analytics into per-link stats            │
│  - maps entities <-> response DTOs                          │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│ ShortUrlRepository / ClickAnalyticsRepository (Spring Data   │
│ JPA)                                                         │
│  - findByShortCode / existsByShortCode                       │
│  - countByShortUrlId / findFirstClickAt / findLastClickAt /   │
│    findDailyClickCounts (grouped aggregation)                │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
        MySQL: short_url table            click_analytics table
   (unique index on short_code,      (indexed on short_url_id and
       index on active)                       clicked_at)
```

Cross-cutting:
- **`GlobalExceptionHandler`** (`@RestControllerAdvice`) — maps `ResourceNotFoundException`, `InvalidUrlException`, `DuplicateShortCodeException`, validation errors, and any uncaught exception to a structured `ApiError` (timestamp, status, error, message, path).
- **`OpenApiConfig`** — exposes Swagger UI / OpenAPI spec for interactive API exploration.

## 2. Tools

- **Spring Boot 3.4.1 / Java 21** — application framework and language baseline.
- **Spring Data JPA + MySQL** — persistence; schema is managed via `hibernate.ddl-auto=update` (auto-generated from entity annotations, not migration-controlled).
- **springdoc-openapi** — generates the OpenAPI spec and Swagger UI from controller annotations.
- **JUnit 5 / Spring Boot Test** — unit and slice tests per layer.

## 3. Data Model

`short_url` table (backing the `ShortUrl` entity):

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, PK, identity | |
| `short_code` | VARCHAR(20), unique, indexed | auto-generated (8 random alphanumeric chars) or caller-supplied |
| `original_url` | VARCHAR(2048) | validated as an absolute `http(s)` URL before storage |
| `click_count` | BIGINT, default 0 | incremented on each successful redirect |
| `active` | BOOLEAN, default true, indexed | exists on the model but nothing currently sets it to `false` — there is no deactivate endpoint |
| `created_at` | TIMESTAMP | set via `@PrePersist` |
| `expires_at` | TIMESTAMP, nullable | **present in the schema but never read** — see Known Limitations in the README |

`click_analytics` table (backing the `ClickAnalytics` entity) — one row per redirect:

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, PK, identity | |
| `short_url_id` | BIGINT, indexed | logically references `short_url.id`, but stored as a plain value — no JPA `@ManyToOne` relation or DB-level foreign-key constraint (see Key Design Decisions) |
| `clicked_at` | TIMESTAMP, indexed | set via `@PrePersist`; indexed to support the daily-breakdown aggregation query |
| `referrer` | VARCHAR(2048), nullable | from the `Referer` request header; frequently null — browsers only send it when navigation originated from a link on another page (not for direct/typed navigation), and some browsers/extensions strip it entirely (see Known Limitations in the README) |
| `user_agent` | VARCHAR(512), nullable | from the `User-Agent` request header, when present |

## 4. Control Flow

**Create (`POST /api/v1/urls`):**
1. Controller validates the request body (`@Valid` — `originalUrl` non-blank and matches an absolute-URL pattern).
2. Service re-validates/normalizes the URL via `UrlValidator` (defense in depth against the same class of input, using `java.net.URI` to require a scheme + host of `http`/`https`).
3. If no custom code was supplied, the service generates an 8-character random code from a 62-character alphabet (`SecureRandom`); if one was supplied, it's lowercased and checked against `[a-zA-Z0-9-]{3,20}`.
4. The service checks `existsByShortCode`, then persists a new `ShortUrl`. *(Note: steps 3–4 are not atomic — see Known Limitations.)*
5. Controller returns `201` with the persisted entity mapped to `ShortUrlResponse`.

**Redirect (`GET /api/v1/{shortCode}`):**
1. Controller reads the `Referer` and `User-Agent` headers off the incoming request.
2. Service looks up the entity by short code; `404` via `ResourceNotFoundException` if absent.
3. If `active` is `false`, also `404`s (though nothing in the current code ever flips `active` to `false`).
4. Click count is incremented and saved, and a `click_analytics` row is written in the same transaction (short_url_id, timestamp, referrer, user agent). *(This makes the write path do more work per redirect — see Known Limitations regarding making this async.)*
5. Controller issues a `302` redirect to `original_url`. *(Deliberately not `301` — see Key Design Decisions: a 301 would let browsers cache the redirect and skip the server on repeat clicks, undercounting `click_count`/`click_analytics`.)*

**Click stats (`GET /api/v1/urls/{shortCode}/stats`):**
1. Service resolves the `ShortUrl` by code; `404` if absent.
2. Runs three aggregate queries against `click_analytics` for that link's id: total count, min/max `clicked_at`, and a `GROUP BY CAST(clicked_at AS date)` breakdown ordered ascending.
3. Assembles `ClickStatsResponse` (short code, total clicks, first/last click timestamps, daily breakdown list) and returns `200`.

## 5. Key Design Decisions

- **Layered architecture (controller → service interface/impl → repository)** was chosen over a transaction-script or single-class design for testability: each layer is independently unit-testable, and the service is programmed against an interface (`UrlShortenerService`) so it can be mocked in controller tests.
- **Random short codes over sequence-based/hash-based encoding** (e.g., base62 of the auto-increment ID): simpler to implement and avoids leaking row-count/creation-order information through the code, at the cost of needing a uniqueness check per creation rather than a guaranteed-unique derivation.
- **`ddl-auto=update` instead of a migration tool (Flyway/Liquibase)**: faster to iterate on for a prototype, but not something to carry into a shared/production environment — schema changes aren't versioned or reviewable as migrations.
- **302 (temporary) redirect, not 301**: originally implemented as `301 Moved Permanently`, which is spec-cacheable by browsers — testing showed that a browser given a 301 once will resolve the short link from its own cache on every subsequent click, never re-hitting the server, so `click_count` and `click_analytics` silently stop incrementing for that visitor. Switched to `302 Found` so every click reaches the server and gets counted. Trade-off: the service loses the browser-caching benefit a 301 gave, and a redirect target can be changed later without stale-cache risk — both acceptable given click accuracy is the core feature.
- **Event table (`click_analytics`) instead of only a counter**: a single `click_count` integer can't answer "clicks over time" or support a future "top links" view, so individual click events are recorded and aggregated on read. Trade-off: this is a write on every redirect (one INSERT plus the existing `click_count` UPDATE) instead of a single UPDATE — acceptable for a prototype, but the reason the README calls out making this write asynchronous as a near-term reliability follow-up.
- **`short_url_id` stored as a plain indexed column, not a JPA `@ManyToOne`**: avoids loading/managing the `ShortUrl` association just to write an analytics row, keeping the redirect's hot path lighter at the cost of no referential-integrity enforcement — there's an index on `short_url_id` for query performance, but no actual foreign-key constraint or cascade behavior at either the entity or schema level.

## 6. Execution Approach

Implementation proceeded layer-by-layer (entity → repository → service → controller → exception handling → tests), which is reflected in the codebase structure but not yet in fine-grained commit history (current history is 3 coarse commits). Task decomposition, the AI-assistance trail, and per-scenario execution notes are tracked separately as those docs are added (see the main [README](../README.md) Project Status section).

## 7. What This Architecture Does Not Yet Address

These are scope gaps, not implementation bugs — tracked in full in the README's Known Limitations:
- A "top links" analytics view across all URLs (per-link stats are now implemented; cross-link aggregation is not)
- Async click recording — analytics writes currently happen synchronously on the redirect path
- Reliability concerns: rate limiting, caching, health checks, retry/circuit-breaking
- Link lifecycle management: update, delete, deactivate, expiration enforcement
- AuthN/AuthZ and multi-tenant ownership of links
- Concurrency-safe short-code allocation (currently check-then-act, not atomic)
