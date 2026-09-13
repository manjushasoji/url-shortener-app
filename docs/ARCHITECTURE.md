# Architecture Overview

## 1. Components

The service is a single Spring Boot application in three layers:

```
Client
  │  HTTP (JSON / redirect)
  ▼
┌───────────────────────────────────────────────────────────┐
│ RateLimitFilter (servlet filter, /api/v1/* only)            │
│  - per-client-IP fixed-window check; 429 short-circuits      │
│    the request before it reaches Spring MVC on excess        │
└───────────────────────────────────────────────────────────┘
                          │
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
│  - increments click_count on redirect, delegates click_     │
│    analytics recording to ClickAnalyticsRecorder (async)     │
│  - aggregates click_analytics into per-link stats            │
│  - maps entities <-> response DTOs                          │
└───────────────────────────────────────────────────────────┘
                    │                        │ (async, different thread)
                    ▼                        ▼
┌─────────────────────────┐   ┌───────────────────────────────┐
│ ShortUrlRepository       │   │ ClickAnalyticsRecorder          │
│  - findByShortCode /     │   │ (interface, mockable as a JDK   │
│    existsByShortCode     │   │ proxy) / ClickAnalyticsRecorder │
└─────────────────────────┘   │ Impl (@Async) — parses browser  │
                    │          │ name (UserAgentParser), saves    │
                    │          │ a click_analytics row            │
                    │          └───────────────────────────────┘
                    │                        │
                    ▼                        ▼
        MySQL: short_url table            click_analytics table
   (unique index on short_code,      (indexed on short_url_id and
       index on active)                       clicked_at)

ClickAnalyticsRepository (Spring Data JPA) also backs the stats
endpoint directly from UrlShortenerServiceImpl (countByShortUrlId /
findFirstClickAt / findLastClickAt / findDailyClickCounts) — the
async path above is only for writing new click events.
```

Cross-cutting:
- **`GlobalExceptionHandler`** (`@RestControllerAdvice`) — maps `ResourceNotFoundException`, `InvalidUrlException`, `DuplicateShortCodeException`, `UrlExpiredException`, validation errors, and any uncaught exception to a structured `ApiError` (timestamp, status, error, message, path).
- **`OpenApiConfig`** — exposes Swagger UI / OpenAPI spec for interactive API exploration.
- **`RateLimitFilter`** (registered via `RateLimitConfig` on `/api/v1/*`, ahead of Spring MVC) — a per-client-IP fixed-window limiter; returns `429` with the same `ApiError` shape when exceeded, before the request reaches the controller.
- **`AsyncConfig`** (`@EnableAsync` + `AsyncConfigurer`) — provides the bounded thread pool `@Async` methods run on, and logs (rather than silently swallows) any exception an async method throws.

## 2. Tools

- **Spring Boot 3.4.1 / Java 21** — application framework and language baseline.
- **Spring Data JPA + MySQL** — persistence; schema is managed via `hibernate.ddl-auto=update` (auto-generated from entity annotations, not migration-controlled).
- **springdoc-openapi** — generates the OpenAPI spec and Swagger UI from controller annotations.
- **Spring Boot Actuator** — exposes `/actuator/health` (with a DB connectivity check) and `/actuator/info` for operational visibility.
- **JUnit 5 / Spring Boot Test** — unit and slice tests per layer.
- **GitHub Actions** (`.github/workflows/ci.yml`) — runs the test suite against a real MySQL service container on every push/PR to `main`. **Dependabot** (`.github/dependabot.yml`) — weekly PRs for outdated/vulnerable Maven and Actions dependencies.

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
| `expires_at` | TIMESTAMP, nullable | optionally set from `CreateShortUrlRequest.expiresAt` (must be a future timestamp, validated via `@Future`); enforced on redirect — see Control Flow |

`click_analytics` table (backing the `ClickAnalytics` entity) — one row per redirect:

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, PK, identity | |
| `short_url_id` | BIGINT, indexed | logically references `short_url.id`, but stored as a plain value — no JPA `@ManyToOne` relation or DB-level foreign-key constraint (see Key Design Decisions) |
| `clicked_at` | TIMESTAMP, indexed | set via `@PrePersist`; indexed to support the daily-breakdown aggregation query |
| `referrer` | VARCHAR(2048), nullable | from the `Referer` request header; frequently null — browsers only send it when navigation originated from a link on another page (not for direct/typed navigation), and some browsers/extensions strip it entirely (see Known Limitations in the README) |
| `user_agent` | VARCHAR(512), nullable | a parsed browser name (`Chrome`, `Firefox`, `Safari`, `Edge`, `Opera`, `Internet Explorer`, `Other`, or `Unknown`) derived from the raw `User-Agent` header via `UserAgentParser` — the raw header itself is not stored (see Known Limitations in the README) |

## 4. Control Flow

**Create (`POST /api/v1/urls`):**
0. `RateLimitFilter` checks the caller's IP against its per-minute quota; over the limit, responds `429` immediately without invoking the controller.
1. Controller validates the request body (`@Valid` — `originalUrl` non-blank and matches an absolute-URL pattern; `expiresAt`, if present, must be in the future).
2. Service re-validates/normalizes the URL via `UrlValidator` (defense in depth against the same class of input, using `java.net.URI` to require a scheme + host of `http`/`https`).
3. If a custom code was supplied, it's lowercased and checked against `[a-zA-Z0-9-]{3,20}`, then a fast `existsByShortCode` pre-check gives an immediate `409` for the common case. If none was supplied, the service generates an 8-character random code from a 62-character alphabet (`SecureRandom`) — no pre-check, since collision odds are negligible.
4. The service attempts `save()`. Because `ShortUrl` uses `GenerationType.IDENTITY`, the INSERT (and any unique-constraint violation) happens synchronously inside `save()`, not on a later flush. A `DataIntegrityViolationException` here means a race: another request took the same code between the pre-check and the insert. For a custom code, this maps straight to `409`. For a generated code, the service retries with a fresh random code, up to 5 attempts, before giving up with `409`. *(This replaces the earlier check-then-act-only approach — see Key Design Decisions.)*
5. Controller returns `201` with the persisted entity (including `expiresAt`, if set) mapped to `ShortUrlResponse`.

**Redirect (`GET /api/v1/{shortCode}`):**
0. `RateLimitFilter` applies the same per-IP check as on create (same filter, same `/api/v1/*` mapping).
1. Controller reads the `Referer` and `User-Agent` headers off the incoming request.
2. Service looks up the entity by short code; `404` via `ResourceNotFoundException` if absent.
3. If `active` is `false`, also `404`s (though nothing in the current code ever flips `active` to `false`).
4. If `expires_at` is set and is in the past, throws `UrlExpiredException` → `410 Gone`.
5. Click count is incremented and saved (still on the request thread — this part stays synchronous, since a lost click count would be visibly wrong on the next metadata fetch). `ClickAnalyticsRecorder.recordClick` is then called, which dispatches to a background thread pool (`@Async`) that parses the browser name (`UserAgentParser`) and writes the `click_analytics` row — the request thread does not wait for this to finish.
6. Controller issues a `302` redirect to `original_url`, which returns as soon as step 5's synchronous part completes — it does not wait on the async analytics write. *(Deliberately not `301` — see Key Design Decisions: a 301 would let browsers cache the redirect and skip the server on repeat clicks, undercounting `click_count`/`click_analytics`.)*

**Click stats (`GET /api/v1/urls/{shortCode}/stats`):**
1. Service resolves the `ShortUrl` by code; `404` if absent.
2. Runs three aggregate queries against `click_analytics` for that link's id: total count, min/max `clicked_at`, and a `GROUP BY CAST(clicked_at AS date)` breakdown ordered ascending.
3. Assembles `ClickStatsResponse` (short code, total clicks, first/last click timestamps, daily breakdown list) and returns `200`.

## 5. Key Design Decisions

- **Layered architecture (controller → service interface/impl → repository)** was chosen over a transaction-script or single-class design for testability: each layer is independently unit-testable, and the service is programmed against an interface (`UrlShortenerService`) so it can be mocked in controller tests.
- **Random short codes over sequence-based/hash-based encoding** (e.g., base62 of the auto-increment ID): simpler to implement and avoids leaking row-count/creation-order information through the code, at the cost of needing a uniqueness check per creation rather than a guaranteed-unique derivation.
- **Save-and-catch-and-retry instead of a stricter check-then-act for short-code creation**: the original implementation only checked `existsByShortCode` before saving, leaving a race window between two concurrent requests generating the same code. Rather than adding a DB-level advisory lock or `SELECT ... FOR UPDATE` (more complex, and unnecessary given how sparse the 62^8 code space is), `createShortUrl` now catches the unique-constraint violation from `save()` itself and retries with a new random code (generated codes) or fails clearly with `409` (custom codes, where retrying with a different code wouldn't honor what the caller asked for). This required removing `@Transactional` from `createShortUrl`: retrying inside one wrapping transaction would fail, because a caught persistence exception marks that transaction rollback-only in Spring/Hibernate — each `save()` attempt now runs in its own implicit transaction (Spring Data's repository methods are `@Transactional` themselves), so a failed attempt doesn't poison the next one.
- **`ddl-auto=update` instead of a migration tool (Flyway/Liquibase)**: faster to iterate on for a prototype, but not something to carry into a shared/production environment — schema changes aren't versioned or reviewable as migrations.
- **302 (temporary) redirect, not 301**: originally implemented as `301 Moved Permanently`, which is spec-cacheable by browsers — testing showed that a browser given a 301 once will resolve the short link from its own cache on every subsequent click, never re-hitting the server, so `click_count` and `click_analytics` silently stop incrementing for that visitor. Switched to `302 Found` so every click reaches the server and gets counted. Trade-off: the service loses the browser-caching benefit a 301 gave, and a redirect target can be changed later without stale-cache risk — both acceptable given click accuracy is the core feature.
- **Event table (`click_analytics`) instead of only a counter**: a single `click_count` integer can't answer "clicks over time" or support a future "top links" view, so individual click events are recorded and aggregated on read. Trade-off: this is a write on every redirect (one INSERT plus the existing `click_count` UPDATE) instead of a single UPDATE — mitigated by making the INSERT asynchronous (see below).
- **`click_analytics` write moved to a separate `@Async` bean (`ClickAnalyticsRecorderImpl`, behind a `ClickAnalyticsRecorder` interface), `click_count` update left synchronous**: the two writes have different failure tolerances — an inaccurate click *count* would be visibly wrong the next time someone fetches metadata, while a missing analytics *event* just slightly undercounts a chart nobody's looking at in real time. Splitting them lets the redirect return as soon as the tolerant part (count) is done, without waiting on the less-critical part (event). This required moving the call into a different Spring bean than `UrlShortenerServiceImpl`, because `@Async`'s proxy only intercepts calls arriving from outside the bean — calling an `@Async` method on `this` from within the same class silently runs synchronously. A failed async write is only logged (`AsyncConfig`'s exception handler), not retried — accepted as a known limitation rather than adding a retry/dead-letter mechanism disproportionate to a prototype.
- **`ClickAnalyticsRecorder` is an interface, not just the `@Async` class directly**: originally a single concrete class, mocking it in `UrlShortenerServiceImplTest` (`@Mock private ClickAnalyticsRecorder ...`) routed through Mockito 5's default "inline" mock maker, which instruments bytecode via a self-attached Java agent. That failed on this project's CI runner (`Could not modify all classes [class java.lang.Object, ...]`) even with `-Djdk.attach.allowAttachSelf=true` set — every test in the class failed identically, since the failure happens at mock creation, before any test body runs. Splitting into an interface (`ClickAnalyticsRecorder`) plus impl (`ClickAnalyticsRecorderImpl`) sidesteps the problem categorically: Mockito mocks interfaces with a plain JDK dynamic proxy, which never touches the inline mock maker, regardless of JDK/CI quirks. Every other `@Mock` in this codebase (the repository interfaces) was already interface-typed, which is why only this one class had the problem.
- **`short_url_id` stored as a plain indexed column, not a JPA `@ManyToOne`**: avoids loading/managing the `ShortUrl` association just to write an analytics row, keeping the redirect's hot path lighter at the cost of no referential-integrity enforcement — there's an index on `short_url_id` for query performance, but no actual foreign-key constraint or cascade behavior at either the entity or schema level.
- **Hand-rolled `UserAgentParser` instead of a UA-parsing library**: the raw `User-Agent` header is a single string that packs multiple browser/engine tokens together for legacy compatibility (e.g. Chrome's UA also contains "Safari" and "AppleWebKit"), which is confusing to read directly in analytics. A small ordered set of substring checks (most-derived browsers like Edge/Opera checked before Chrome, since they also contain a "Chrome" token) covers the common desktop/mobile browsers without adding a dependency. Trade-off: it won't correctly classify less common or future browsers (they fall into `Other`), whereas a maintained library (e.g. `ua-parser`) would stay current with new UA formats at the cost of an added dependency.
- **Hand-rolled `FixedWindowRateLimiter` (servlet filter) instead of a library like Bucket4j**: a fixed window per client IP, backed by a plain `ConcurrentHashMap`, is simple enough to review at a glance and needs zero new runtime dependencies — reasonable for a single-instance prototype. Trade-off vs. a token-bucket library: fixed windows allow a burst of up to 2x the limit right at a window boundary (e.g. 30 requests in the last second of one window, then another 30 in the first second of the next), where a token bucket smooths this out. A bigger limitation is that this is in-memory and per-instance — see Known Limitations in the README for what breaks if this ever runs behind a load balancer or across multiple instances.
- **DB credentials as env-var-overridable properties with a committed local-dev default, not a secrets manager**: `${DB_URL:...}`/`${DB_USERNAME:root}`/`${DB_PASSWORD:admin1234}` in `application.properties` mean any real environment (including CI) sets real values via environment variables and the committed default is never exercised there, while local development stays zero-config. Trade-off: a placeholder credential string is still readable in git history, which a secrets-manager-only approach (no default, fail fast if unset) would avoid — deferred here in favor of not adding required setup friction to a prototype; see Known Limitations in the README.
- **CI runs against a real MySQL service container, not a mocked/in-memory DB**: `UrlShortenerAppApplicationTests` is a `@SpringBootTest` that boots the full context, which fails immediately without a reachable datasource — so CI needed either a real MySQL container or a switch to something like H2 for tests. Chose the MySQL container specifically because it exercises the same SQL dialect and JPA behavior (e.g. the `CAST(... AS date)` aggregation query) the app actually runs on in every environment, rather than risking H2-only behavior diverging from MySQL in ways that only surface after deploying.

## 6. Execution Approach

Implementation proceeded layer-by-layer (entity → repository → service → controller → exception handling → tests), which is reflected in the codebase structure but not yet in fine-grained commit history (current history is 3 coarse commits). Task decomposition, the AI-assistance trail, and per-scenario execution notes are tracked separately as those docs are added (see the main [README](../README.md) Project Status section).

## 7. What This Architecture Does Not Yet Address

These are scope gaps, not implementation bugs — tracked in full in the README's Known Limitations:
- A "top links" analytics view across all URLs (per-link stats are now implemented; cross-link aggregation is not)
- Reliability concerns: caching, retry/circuit-breaking (health checks, rate limiting, and async click recording are now implemented)
- Link lifecycle management: update, delete, deactivate (expiration is now implemented and enforced)
- AuthN/AuthZ and multi-tenant ownership of links
- `/actuator/health` detail exposure has no access control — fine for local/prototype use, not for a shared deployment
- Rate limiting is in-memory/per-instance and keyed on the immediate TCP peer address — breaks down behind a load balancer or across multiple instances (see Known Limitations in the README)
- Async click recording has no delivery guarantee — a failed or queue-rejected write is logged and dropped, not retried (see Known Limitations in the README)
- CI runs the test suite but no static analysis, linting, or dependency-CVE scanning beyond Dependabot's alerts
- The committed DB credential defaults are env-var-overridable, not eliminated — see Known Limitations in the README
