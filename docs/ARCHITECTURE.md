# Architecture Overview

## 1. Components

The service is a single Spring Boot application in three layers:

```
Client
  │  HTTP (JSON / redirect)
  ▼
┌───────────────────────────────────────────────────────────┐
│ UrlShortenerController                                     │
│  - POST /api/v1/urls          (create)                     │
│  - GET  /api/v1/urls/{code}   (metadata)                   │
│  - GET  /api/v1/{code}        (redirect)                   │
│  - request validation (Bean Validation on DTOs)            │
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
│  - increments click_count on redirect                       │
│  - maps entities <-> response DTOs                          │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│ ShortUrlRepository (Spring Data JPA)                        │
│  - findByShortCode / existsByShortCode                       │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
                    MySQL: short_url table
       (unique index on short_code, index on active)
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

## 4. Control Flow

**Create (`POST /api/v1/urls`):**
1. Controller validates the request body (`@Valid` — `originalUrl` non-blank and matches an absolute-URL pattern).
2. Service re-validates/normalizes the URL via `UrlValidator` (defense in depth against the same class of input, using `java.net.URI` to require a scheme + host of `http`/`https`).
3. If no custom code was supplied, the service generates an 8-character random code from a 62-character alphabet (`SecureRandom`); if one was supplied, it's lowercased and checked against `[a-zA-Z0-9-]{3,20}`.
4. The service checks `existsByShortCode`, then persists a new `ShortUrl`. *(Note: steps 3–4 are not atomic — see Known Limitations.)*
5. Controller returns `201` with the persisted entity mapped to `ShortUrlResponse`.

**Redirect (`GET /api/v1/{shortCode}`):**
1. Service looks up the entity by short code; `404` via `ResourceNotFoundException` if absent.
2. If `active` is `false`, also `404`s (though nothing in the current code ever flips `active` to `false`).
3. Click count is incremented and saved.
4. Controller issues a `301` redirect to `original_url`.

## 5. Key Design Decisions

- **Layered architecture (controller → service interface/impl → repository)** was chosen over a transaction-script or single-class design for testability: each layer is independently unit-testable, and the service is programmed against an interface (`UrlShortenerService`) so it can be mocked in controller tests.
- **Random short codes over sequence-based/hash-based encoding** (e.g., base62 of the auto-increment ID): simpler to implement and avoids leaking row-count/creation-order information through the code, at the cost of needing a uniqueness check per creation rather than a guaranteed-unique derivation.
- **`ddl-auto=update` instead of a migration tool (Flyway/Liquibase)**: faster to iterate on for a prototype, but not something to carry into a shared/production environment — schema changes aren't versioned or reviewable as migrations.
- **301 (permanent) redirect**: matches typical URL-shortener semantics (and lets browsers cache the redirect), but means a redirect target can never be safely changed after creation without risking stale client-side caches — a trade-off worth revisiting once/if an "update URL" feature is added.

## 6. Execution Approach

Implementation proceeded layer-by-layer (entity → repository → service → controller → exception handling → tests), which is reflected in the codebase structure but not yet in fine-grained commit history (current history is 3 coarse commits). Task decomposition, the AI-assistance trail, and per-scenario execution notes are tracked separately as those docs are added (see the main [README](../README.md) Project Status section).

## 7. What This Architecture Does Not Yet Address

These are scope gaps, not implementation bugs — tracked in full in the README's Known Limitations:
- Analytics beyond a single counter column
- Reliability concerns: rate limiting, caching, health checks, retry/circuit-breaking
- Link lifecycle management: update, delete, deactivate, expiration enforcement
- AuthN/AuthZ and multi-tenant ownership of links
- Concurrency-safe short-code allocation (currently check-then-act, not atomic)
