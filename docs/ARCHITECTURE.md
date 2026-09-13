# Architecture Overview

## 1. Components

The service is a single Spring Boot application in three layers:

```
Client
  │  HTTP (JSON / redirect)
  ▼
┌───────────────────────────────────────────────────────────┐
│ Spring Security filter chain (SecurityConfig)                │
│  - runs before RateLimitFilter (see Key Design Decisions)    │
│  - /api/v1/urls/** — HTTP Basic, requires ROLE_ADMIN          │
│    (** also matches the bare /api/v1/urls, i.e. list)         │
│  - GET /{code} (redirect, root) — permitAll                    │
│  - everything else declared public — permitAll                │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│ RateLimitFilter (servlet filter on /*, exempting actuator,   │
│   swagger-ui, v3/api-docs, webjars, error)                    │
│  - per-client-IP fixed-window check; 429 short-circuits      │
│    the request before it reaches Spring MVC on excess        │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────┐   ┌────────────────────────┐
│ UrlShortenerController         │   │ RedirectController      │
│  (admin-only, see above)       │   │  (public)                │
│  - POST  /api/v1/urls          │   │  - GET /{code}            │
│    (create)                    │   │    (redirect; {code} is   │
│  - GET   /api/v1/urls          │   │    regex-constrained to   │
│    (list, paginated)           │   │    ShortCodes.PATTERN)    │
│  - GET   /api/v1/urls/{code}   │   │  - extracts Referer /     │
│    (metadata)                  │   │    User-Agent headers     │
│  - PATCH /api/v1/urls/{code}   │   │    for analytics           │
│    (update active/expiresAt)   │   └────────────────────────┘
│  - DELETE /api/v1/urls/{code}  │
│    (delete + its analytics)    │
│  - GET   /api/v1/urls/{code}/  │
│    stats (click analytics)     │
│  - request validation (Bean    │
│    Validation on DTOs)         │
└──────────────────────────────┘
    both delegate all business logic to the service layer
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│ UrlShortenerService (interface) / UrlShortenerServiceImpl   │
│  - normalizes + validates the submitted URL (UrlValidator)  │
│  - generates an 8-char random short code, or normalizes a   │
│    caller-supplied custom code                              │
│  - enforces short-code uniqueness before persisting          │
│  - on redirect: reads via ShortUrlCache (cached), increments │
│    click_count via a direct uncached UPDATE, delegates       │
│    click_analytics recording to ClickAnalyticsRecorder(async)│
│  - on update: evicts ShortUrlCache for that shortCode         │
│  - aggregates click_analytics into per-link stats            │
│  - maps entities <-> response DTOs                          │
└───────────────────────────────────────────────────────────┘
              │                    │                    │ (async, different thread)
              ▼                    ▼                    ▼
┌──────────────────────┐ ┌──────────────────────┐ ┌──────────────────────────┐
│ ShortUrlRepository     │ │ ShortUrlCache          │ │ ClickAnalyticsRecorder     │
│  - findByShortCode /   │ │ (interface) /          │ │ (interface, mockable as a │
│    existsByShortCode   │ │ ShortUrlCacheImpl       │ │ JDK proxy) /               │
│  - incrementClickCount │ │ (@Cacheable) — on a     │ │ ClickAnalyticsRecorderImpl │
│    (atomic UPDATE,     │ │ miss, reads             │ │ (@Async) — parses browser  │
│    never cached)       │ │ ShortUrlRepository and   │ │ name (UserAgentParser),    │
│                        │ │ caches an immutable      │ │ saves a click_analytics    │
│                        │ │ CachedShortUrl snapshot  │ │ row                        │
│                        │ │ (id/url/active/expiresAt │ │                            │
│                        │ │ — never clickCount)      │ │                            │
└──────────────────────┘ └──────────────────────┘ └──────────────────────────┘
              │                    │ (miss only)                    │
              ▼                    ▼                                ▼
        MySQL: short_url table            click_analytics table
   (unique index on short_code,      (indexed on short_url_id and
       index on active)                       clicked_at)

ClickAnalyticsRepository (Spring Data JPA) also backs the stats
endpoint directly from UrlShortenerServiceImpl (countByShortUrlId /
findFirstClickAt / findLastClickAt / findDailyClickCounts) — the
async path above is only for writing new click events.
```

Cross-cutting:
- **`GlobalExceptionHandler`** (`@RestControllerAdvice`) — maps `ResourceNotFoundException`, `InvalidUrlException`, `DuplicateShortCodeException`, `UrlExpiredException`, `InvalidUpdateRequestException`, `NoResourceFoundException` (Spring's own exception for an unmatched `GET`/`HEAD` path — see Key Design Decisions), validation errors, and any uncaught exception to a structured `ApiError` (timestamp, status, error, message, path). This is what produces a `404` for a typo'd/unmapped path; it does *not* handle Spring Security rejections, which bypass the exception-resolver chain entirely (see `ApiErrorController` below).
- **`OpenApiConfig`** — exposes Swagger UI / OpenAPI spec for interactive API exploration.
- **`RateLimitFilter`** (registered via `RateLimitConfig` on `/*` with `shouldNotFilter` exempting `/actuator`, `/swagger-ui`, `/v3/api-docs`, `/webjars`, `/error`; ahead of Spring MVC) — a per-client-IP fixed-window limiter; returns `429` with the same `ApiError` shape when exceeded, before the request reaches the controller.
- **`SecurityConfig`** — HTTP Basic Auth, `ROLE_ADMIN` required for `/api/v1/urls/**`, everything else (including the redirect) public. See Key Design Decisions for why Basic Auth over JWT, why a single in-memory user, and how this interacts with `RateLimitFilter`.
- **`ApiErrorController`** (implements `ErrorController`, mapped to `/error`) — handles everything that reaches Spring Boot's `/error` forwarding *without* going through `@RestControllerAdvice`, which in practice means every Spring Security rejection (`401`/`403`, dispatched via `response.sendError()`) plus non-`GET`/`HEAD` unmapped paths. Despite the name, this is not a rare corner case: it runs on every unauthenticated request to an admin endpoint — see Key Design Decisions for a real bug this caused (a `401` silently reported as `500`) and how it was fixed.
- **`AsyncConfig`** (`@EnableAsync` + `AsyncConfigurer`) — provides the bounded thread pool `@Async` methods run on, and logs (rather than silently swallows) any exception an async method throws.
- **`CacheConfig`** (`@EnableCaching` + a `CacheManagerCustomizer<CaffeineCacheManager>`) — configures the single `shortUrls` cache backing `ShortUrlCache` (5-minute TTL, 10,000-entry cap). See Key Design Decisions for what is and isn't cached, and why.

## 2. Tools

- **Spring Boot 3.4.1 / Java 21** — application framework and language baseline.
- **Spring Data JPA + MySQL** — persistence; schema is managed via `hibernate.ddl-auto=update` (auto-generated from entity annotations, not migration-controlled).
- **springdoc-openapi** — generates the OpenAPI spec and Swagger UI from controller annotations.
- **Spring Boot Actuator** — exposes `/actuator/health` (with a DB connectivity check) and `/actuator/info` for operational visibility.
- **Spring Security** — HTTP Basic Auth, one in-memory admin user, `ROLE_ADMIN`-gated management endpoints. `spring-security-test` provides `@WithMockUser`/`@WithAnonymousUser` for the `@WebMvcTest` slices.
- **Spring Cache + Caffeine** — in-memory caching for the redirect lookup only; see `CacheConfig`/`ShortUrlCache` above and the caching bullets in Key Design Decisions.
- **JUnit 5 / Spring Boot Test** — unit and slice tests per layer. Surefire runs with `-Dnet.bytebuddy.experimental=true` so `mvn test` still works on a JDK newer than the bundled Mockito/Byte Buddy officially supports (relevant for local dev on a bleeding-edge JDK; CI's pinned JDK 21 doesn't need it).
- **GitHub Actions** (`.github/workflows/ci.yml`) — runs `./mvnw verify` (Checkstyle → compile → test suite → SpotBugs) against a real MySQL service container on every push/PR to `main`. **Checkstyle** (`config/checkstyle.xml`) and **SpotBugs** (`config/spotbugs-exclude.xml` for the suppressions, each with a reason) both fail the build on any violation. **Dependabot** (`.github/dependabot.yml`) — weekly PRs for outdated/vulnerable Maven and Actions dependencies.
- **Maven Wrapper** (`mvnw` / `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `wrapperVersion=3.3.4`, `distributionUrl` pinned to Maven `3.9.16`) — no local Maven install needed; `./mvnw`/`mvnw.cmd` download and run the pinned version themselves. CI uses the same wrapper invocation, so local dev and CI are guaranteed to run the identical Maven version — closing off one more axis of the environment drift that caused real problems earlier in this project (see Key Design Decisions). `mvnw` needs its executable bit set in git (`100755`) to run on Linux/macOS without an explicit `chmod +x` first; this was initially committed as `100644` and had to be corrected.

## 3. Data Model

`short_url` table (backing the `ShortUrl` entity):

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, PK, identity | |
| `short_code` | VARCHAR(20), unique, indexed | auto-generated (8 random alphanumeric chars) or caller-supplied |
| `original_url` | VARCHAR(2048) | validated as an absolute `http(s)` URL before storage |
| `click_count` | BIGINT, default 0 | incremented on each successful redirect via a direct atomic `UPDATE` (`ShortUrlRepository.incrementClickCount`) — deliberately never part of `ShortUrlCache`'s cached data, and never read from the cache either; see Key Design Decisions |
| `active` | BOOLEAN, default true, indexed | settable via `PATCH /api/v1/urls/{shortCode}` (`{ "active": false }` deactivates a link; enforced on redirect — see Control Flow) |
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
0. `SecurityConfig`'s filter chain runs first, ahead of `RateLimitFilter`: requires HTTP Basic credentials for the configured admin user (`ROLE_ADMIN`) — missing/invalid credentials → `401`, valid credentials without the role → `403` (not reachable today since the only user has the role, but the check exists regardless). Then `RateLimitFilter` checks the caller's IP against its per-minute quota; over the limit, responds `429`. Either short-circuits before the controller runs.
1. Controller validates the request body (`@Valid` — `originalUrl` non-blank and matches an absolute-URL pattern; `expiresAt`, if present, must be in the future).
2. Service re-validates/normalizes the URL via `UrlValidator` (defense in depth against the same class of input, using `java.net.URI` to require a scheme + host of `http`/`https`).
3. If a custom code was supplied, it's lowercased and checked against `[a-zA-Z0-9-]{3,20}`, then a fast `existsByShortCode` pre-check gives an immediate `409` for the common case. If none was supplied, the service generates an 8-character random code from a 62-character alphabet (`SecureRandom`) — no pre-check, since collision odds are negligible.
4. The service attempts `save()`. Because `ShortUrl` uses `GenerationType.IDENTITY`, the INSERT (and any unique-constraint violation) happens synchronously inside `save()`, not on a later flush. A `DataIntegrityViolationException` here means a race: another request took the same code between the pre-check and the insert. For a custom code, this maps straight to `409`. For a generated code, the service retries with a fresh random code, up to 5 attempts, before giving up with `409`. *(This replaces the earlier check-then-act-only approach — see Key Design Decisions.)*
5. Controller returns `201` with the persisted entity (including `expiresAt`, if set) mapped to `ShortUrlResponse`.

**Update (`PATCH /api/v1/urls/{shortCode}`):**
0. Same `SecurityConfig` (`ROLE_ADMIN`) and `RateLimitFilter` checks as create, in that order.
1. Controller validates the request body (`@Valid` — `expiresAt`, if present, must be in the future; no constraint on `active` since `Boolean` is inherently optional).
2. Service rejects the request with `400` (`InvalidUpdateRequestException`) if both `active` and `expiresAt` are `null` — before even looking up the entity, so a no-op request never touches the database.
3. Looks up the entity; `404` if absent.
4. Applies only the non-null fields: `active` if provided, `expiresAt` if provided. A `null` field is left as-is, not cleared — see Key Design Decisions for why, and Known Limitations in the README for what that means for clearing an existing `expiresAt`.
5. Saves, evicts `ShortUrlCache`'s entry for this `shortCode` (`@CacheEvict`, so the next redirect sees the change immediately — on this instance; see Known Limitations for the multi-instance caveat), and returns `200` with the updated `ShortUrlResponse`.

**List (`GET /api/v1/urls`):**
0. Same `SecurityConfig` (`ROLE_ADMIN`) and `RateLimitFilter` checks as create. This is the one admin endpoint with no trailing segment, so its protection depends on `/api/v1/urls/**` matching zero segments after `urls` — it does (`**` is zero-or-more), and `UrlShortenerControllerTest` has a dedicated `401` test proving it.
1. Spring Data's `Pageable` resolver builds the page request from `page`/`size`/`sort` query params, defaulting to page 0, size 20, `createdAt` descending (`@PageableDefault`); `spring.data.web.pageable.max-page-size=100` clamps any larger `size`.
2. Service runs `findAll(pageable)` and maps each entity to `ShortUrlResponse`, wrapped in `PagedResponse` — a five-field envelope (`content`, `page`, `size`, `totalElements`, `totalPages`) rather than Spring's `Page` object directly, whose JSON shape is an implementation detail Spring itself warns against exposing.

**Delete (`DELETE /api/v1/urls/{shortCode}`):**
0. Same `SecurityConfig` (`ROLE_ADMIN`) and `RateLimitFilter` checks as create.
1. Looks up the entity; `404` if absent — before any delete runs, so a bad code never partially deletes anything.
2. In one transaction: bulk-deletes the link's `click_analytics` rows (`ClickAnalyticsRepository.deleteAllByShortUrlId`, a single JPQL `DELETE`, since there is no FK cascade to do it — see the `short_url_id` Key Design Decision), then deletes the `short_url` row.
3. Evicts `ShortUrlCache` for this `shortCode` (`@CacheEvict`, same reasoning as update: without it the deleted code would keep redirecting from cache until the TTL), and returns `204`. The code is immediately free for reuse by a new create.

**Redirect (`GET /{shortCode}`, `RedirectController`):**
0. `SecurityConfig` permits `GET /*` with no authentication — see Key Design Decisions for why the redirect stays public while everything else doesn't, and why it lives at the root. `RateLimitFilter` still applies the same per-IP check as on create — rate limiting isn't auth-gated. The path variable is regex-constrained to `ShortCodes.PATTERN` (`[A-Za-z0-9-]{3,20}`), so a root path that isn't shaped like a short code (`/favicon.ico`, `/swagger-ui.html`) never matches this handler.
1. Controller reads the `Referer` and `User-Agent` headers off the incoming request.
2. Service calls `ShortUrlCache.lookupForRedirect(shortCode)`. On a cache hit, this returns instantly with no database access. On a miss, `ShortUrlCacheImpl` queries `ShortUrlRepository.findByShortCode`, throws `ResourceNotFoundException` (→ `404`) if absent — and, since `@Cacheable` only stores a value on normal return, that "not found" result is never itself cached — or maps the entity into an immutable `CachedShortUrl(id, originalUrl, active, expiresAt)` and caches *that*, not the JPA entity.
3. If `active` is `false`, `404`s (settable via `PATCH /api/v1/urls/{shortCode}` — see above; a stale cached `true` here is exactly what `@CacheEvict` on that endpoint exists to prevent).
4. If `expiresAt` is set and is in the past, throws `UrlExpiredException` → `410 Gone`. This comparison is always against `LocalDateTime.now()` at request time, so a cached (but unchanged) `expiresAt` value never itself goes stale — only `active`, a value that actually gets mutated by a write, needs the cache eviction; see Key Design Decisions.
5. `ShortUrlRepository.incrementClickCount(cached.id())` runs as a direct atomic `UPDATE ... SET click_count = click_count + 1` — always executed, cache hit or miss, and never itself cached, so a lost or duplicated increment isn't possible in the way a naive load-then-cache-the-whole-entity design would risk. `ClickAnalyticsRecorder.recordClick` is then called, dispatching to a background thread pool (`@Async`) that parses the browser name and writes the `click_analytics` row — the request thread doesn't wait for this to finish.
6. Controller issues a `302` redirect to `originalUrl`, returning as soon as step 5's synchronous part (the click-count update) completes — it does not wait on the async analytics write. *(Deliberately not `301` — see Key Design Decisions: a 301 would let browsers cache the redirect and skip the server on repeat clicks, undercounting `click_count`/`click_analytics`.)*

**Click stats (`GET /api/v1/urls/{shortCode}/stats`):**
0. Same `SecurityConfig` (`ROLE_ADMIN`) and `RateLimitFilter` checks as create.
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
- **`ClickAnalyticsRecorder` is an interface, not just the `@Async` class directly**: originally a single concrete class, mocking it in `UrlShortenerServiceImplTest` (`@Mock private ClickAnalyticsRecorder ...`) failed with `MockitoException: Could not modify all classes [class java.lang.Object, ...]`, every test in the class failing identically since the failure happens at mock creation, before any test body runs. The split into an interface plus `ClickAnalyticsRecorderImpl` was made on the hypothesis that this was about Mockito's inline mock maker needing bytecode retransformation for concrete classes specifically. That hypothesis was wrong: the actual root cause (confirmed by the full stack trace) is Byte Buddy not yet recognizing a JDK newer than it was built against (`Java N is not supported by the current version of Byte Buddy`) — this breaks mocking *any* type, interface or class, on such a JDK, since Byte Buddy generates a proxy either way. The real fix is the `-Dnet.bytebuddy.experimental=true` Surefire flag (see Tools/README). The interface split is kept regardless, since it matches the `UrlShortenerService`/`Impl` pattern already used in this codebase — just not as a fix for this particular error.
- **`short_url_id` stored as a plain indexed column, not a JPA `@ManyToOne`**: avoids loading/managing the `ShortUrl` association just to write an analytics row, keeping the redirect's hot path lighter at the cost of no referential-integrity enforcement — there's an index on `short_url_id` for query performance, but no actual foreign-key constraint or cascade behavior at either the entity or schema level.
- **Hand-rolled `UserAgentParser` instead of a UA-parsing library**: the raw `User-Agent` header is a single string that packs multiple browser/engine tokens together for legacy compatibility (e.g. Chrome's UA also contains "Safari" and "AppleWebKit"), which is confusing to read directly in analytics. A small ordered set of substring checks (most-derived browsers like Edge/Opera checked before Chrome, since they also contain a "Chrome" token) covers the common desktop/mobile browsers without adding a dependency. Trade-off: it won't correctly classify less common or future browsers (they fall into `Other`), whereas a maintained library (e.g. `ua-parser`) would stay current with new UA formats at the cost of an added dependency.
- **Hand-rolled `FixedWindowRateLimiter` (servlet filter) instead of a library like Bucket4j**: a fixed window per client IP, backed by a plain `ConcurrentHashMap`, is simple enough to review at a glance and needs zero new runtime dependencies — reasonable for a single-instance prototype. Trade-off vs. a token-bucket library: fixed windows allow a burst of up to 2x the limit right at a window boundary (e.g. 30 requests in the last second of one window, then another 30 in the first second of the next), where a token bucket smooths this out. A bigger limitation is that this is in-memory and per-instance — see Known Limitations in the README for what breaks if this ever runs behind a load balancer or across multiple instances.
- **DB credentials as env-var-overridable properties with a committed local-dev default, not a secrets manager**: `${DB_URL:...}`/`${DB_USERNAME:root}`/`${DB_PASSWORD:admin1234}` in `application.properties` mean any real environment (including CI) sets real values via environment variables and the committed default is never exercised there, while local development stays zero-config. Trade-off: a placeholder credential string is still readable in git history, which a secrets-manager-only approach (no default, fail fast if unset) would avoid — deferred here in favor of not adding required setup friction to a prototype; see Known Limitations in the README.
- **CI runs against a real MySQL service container, not a mocked/in-memory DB**: `UrlShortenerAppApplicationTests` is a `@SpringBootTest` that boots the full context, which fails immediately without a reachable datasource — so CI needed either a real MySQL container or a switch to something like H2 for tests. Chose the MySQL container specifically because it exercises the same SQL dialect and JPA behavior (e.g. the `CAST(... AS date)` aggregation query) the app actually runs on in every environment, rather than risking H2-only behavior diverging from MySQL in ways that only surface after deploying.
- **`PATCH` update treats a `null`/omitted field as "leave unchanged," never as "clear it"**: `UpdateShortUrlRequest` has two optional fields, `active` and `expiresAt`. Without extra tooling (e.g. a `JsonNullable`-style wrapper), Jackson can't distinguish a field the client omitted from one explicitly sent as `null` — both deserialize identically. Rather than add that complexity for a single field, one convention had to be picked, and "unchanged" matches how most PATCH APIs behave and what most clients expect. The trade-off, called out in the README: there is currently no way to clear an already-set `expiresAt` back to permanent through this endpoint.
- **Validate "at least one field provided" before the repository lookup, not after**: `updateShortUrl` throws `InvalidUpdateRequestException` for an all-null request before calling `findByShortCode`, so a malformed/no-op request never touches the database — a `404` should only ever mean "this code doesn't exist," not "your request was empty and we happened to check."
- **Custom `ErrorController` instead of `throw-exception-if-no-handler-found` + `add-mappings=false`**: the latter is the more commonly documented way to get a proper `NoHandlerFoundException` for unmapped paths, but `add-mappings=false` also disables Spring Boot's default `/webjars/**` resource mapping — which is how springdoc-openapi serves Swagger UI's bundled static assets — in the same property check as the general static-file mapping. Since this couldn't be verified locally (no build tool in this environment) and breaking a documented, working feature would be a worse regression than the problem being solved, `ApiErrorController` (implementing `ErrorController`, replacing Spring Boot's default one) was used instead: it touches no configuration, can't affect Swagger UI/Actuator/any other route, and is Spring Boot's own documented extension point for customizing `/error` handling.
- **`ApiErrorController` alone turned out not to fix unmapped-path requests in practice — a second, more targeted fix was needed**: after merging the above, an actual unmapped GET request (e.g. `/api/v2/wcom`) still returned a generic `500` from `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)`. Root cause: as of Spring Framework 6.1 (bundled with this project's Spring Boot 3.4.1), an unmatched `GET`/`HEAD` request causes `ResourceHttpRequestHandler` to throw `NoResourceFoundException` *through the normal `HandlerExceptionResolver`/controller-advice chain* — meaning it's handled by `@RestControllerAdvice` directly and never reaches `/error` (where `ApiErrorController` lives) at all. Because no handler for that specific exception type existed, it fell through to the generic catch-all and got mapped to `500` instead of `404`. Fixed by adding `GlobalExceptionHandler.handleNoResourceFound(NoResourceFoundException)`, which — being more specific than `Exception.class` — now wins first and returns a proper `404` with a purpose-written message. `ApiErrorController` is kept as a fallback for whatever else might still reach `/error`, but isn't what handles the common case; this is a case where the first fix addressed a real but different risk (protecting Swagger UI) without being verified against the actual reported symptom, since there was no local way to run the app and hit it directly.
- **`ApiErrorController` read status from the wrong source — fixed after adding Spring Security surfaced it**: the controller originally derived its response status by reading a `"status"` key out of the `Map<String, Object>` returned by Spring Boot's `DefaultErrorAttributes`. That works for the `NoResourceFoundException` path (framework populates it reliably there), but adding `SecurityConfig` revealed it doesn't for a security-triggered rejection: `BasicAuthenticationEntryPoint`/the access-denied handler calls `response.sendError(401, "Unauthorized")` directly rather than throwing through the exception-resolver chain that normally populates that map's `"status"` entry — so `attributes.getOrDefault("status", 500)` silently fell back to `500`, while `"message"` (sourced differently) came through as "Unauthorized" — producing a `500` response whose body claimed to be about an authorization failure. Fixed by reading `HttpServletResponse.getStatus()` directly instead: `sendError()`/`setStatus()` set it synchronously before any forward happens, so it's reliable regardless of which mechanism (thrown exception vs. direct `sendError()`) produced the error. Every path that actually reaches `/error` in this app today goes through `sendError()` (Security's `401`/`403`, and `DispatcherServlet.noHandlerFound()` for non-`GET` unmapped paths), so this is a strict improvement with no known regression case, but it was found by a real user hitting the real endpoint, not by anything this session could verify without a running instance — a concrete reminder of the limits of hand-verified-by-reading changes in an environment with no local build/run.
- **`RedirectController` split out from `UrlShortenerController` specifically to support security gating, not just for organization**: the redirect is the one endpoint that must stay reachable by anonymous visitors — that's the entire point of a URL shortener — while creating/reading/updating/inspecting stats are management operations that shouldn't be. Once those two groups need different `authorizeHttpRequests` treatment, keeping them in one controller would mean the security rule has to reach inside a single class and reason about which method needs which policy; two controllers let the rule be expressed once, cleanly, as two path-based matchers (see the next two bullets), with each controller's own doc comment stating which policy applies to everything in it.
- **HTTP Basic Auth with a single in-memory admin, not JWT/OAuth**: satisfies "only an admin can do X" with infrastructure Spring Security provides out of the box — no token issuance endpoint, no expiry/refresh logic, no secret-signing-key management to get right. Trade-off: Basic Auth re-sends credentials on every request (base64, not encrypted — safe only over HTTPS, which this app doesn't enforce itself) and has no session/logout concept; a real multi-user product would need JWT or OAuth2 plus a persisted user store. Chosen deliberately for a prototype scored on engineering judgment under real constraints: this session has no way to run the app and verify a token flow actually works end to end, whereas Basic Auth's behavior is fully specified and testable with a plain `curl -u`.
- **Security matcher order: `/api/v1/urls/**` (admin) declared before `GET /*` (public), not after**: `authorizeHttpRequests` matches top-to-bottom and stops at the first hit — it is not "most specific pattern wins" the way some routing systems work. The two patterns are structurally disjoint (`/*` is exactly one segment; everything admin has at least three), so the order doesn't change behavior today — but the admin rule stays first so that any future overlap fails safe (admin-gated) rather than silently falling through to the public rule. Note that `**` matches zero segments too: `/api/v1/urls/**` covers the bare `/api/v1/urls` list endpoint, which `UrlShortenerControllerTest` proves with a dedicated `401` test.
- **The redirect lives at `/{shortCode}`, not `/api/v1/{shortCode}`, and the path variable is regex-constrained**: a short link that isn't short defeats its own purpose, and the `/api/v1` prefix was only ever there because the redirect started life in the same controller as the admin API. Moving it to the root raised a real routing question, though: at `/{anything}`, the handler would otherwise match `/favicon.ico` (which every browser requests automatically), `/swagger-ui.html`, and any other single-segment path — each becoming a failed short-code lookup, and the favicon case a *cached-miss-per-browser* on the hot path. Spring MVC's `{var:regex}` syntax fixes this at the mapping level: `/{shortCode:[A-Za-z0-9-]{3,20}}` (the regex is `ShortCodes.PATTERN`, the same constant `normalizeCustomCode` validates against, so the two can't drift). A non-matching root path simply isn't this handler's and gets the normal 404. The old `/api/v1/{shortCode}` path is not kept as a compatibility route.
- **`RateLimitFilter` registered on `/*` with an exemption list, rather than enumerating what to limit**: with the redirect at the root, the old `/api/v1/*` prefix mapping wouldn't cover it, and a servlet URL pattern can't express "one segment that looks like a short code". Two options: register on `/*` and exempt the operational paths (actuator, Swagger UI + its assets, the OpenAPI spec, `/error`), or register on `/*` and re-implement the short-code regex inside the filter. The exemption list won because it makes the intent legible in one place — *everything is rate-limited except these* — and fails safe: a new endpoint is throttled by default rather than silently unthrottled. Swagger UI is the concrete reason the exemption is needed at all: opening it loads a dozen assets, which would burn most of a 30-request window before the user clicked anything.
- **Delete removes the analytics rows explicitly, in the same transaction, analytics first**: `click_analytics.short_url_id` is a plain indexed column with no FK (a deliberate hot-path trade-off, above), so nothing cascades — the service has to do it. One bulk JPQL `DELETE` rather than Spring Data's derived `deleteByShortUrlId`, which would load every row as an entity and delete them one at a time; a popular link can have thousands. Analytics go first so that if the second statement fails, the transaction rolls both back and the link is still intact with its data, rather than a link surviving with its history gone. The accepted gap (README Known Limitations): a redirect that already passed its lookup can still enqueue an async `recordClick` after the delete commits, leaving one orphan analytics row that nothing will ever read.
- **`RateLimitFilter` is not security-aware, and that's an accepted gap, not an oversight**: Spring Security's filter chain runs ahead of `RateLimitFilter` in servlet filter order, so a request Security rejects (`401`/`403`) never reaches the rate limiter — repeated bad-credential attempts against an admin endpoint aren't throttled by anything in this app. Reordering the filters to rate-limit *before* authentication was considered and deliberately not done: it would mean an attacker's failed attempts consume the same quota bucket as legitimate admin traffic from that IP, and getting filter-order interactions with Spring Security's own chain right is exactly the kind of change this session has no way to verify without a running instance to test against. Documented as a known limitation instead of guessed at.
- **`ShortUrlCache` caches a small immutable projection (`CachedShortUrl`), never the JPA `ShortUrl` entity itself**: caching the mutable entity directly was considered and rejected. Two failure modes made it unsafe: Caffeine stores object references in-memory, so mutating a cached entity's `click_count` in place would silently leak the write into the cache without an explicit cache-update call — implicit, fragile behavior that would also behave *differently* under a future distributed cache (which serializes/deserializes, producing a genuinely detached copy, not a shared reference). The clean fix is structural, not a workaround: `click_count` simply isn't part of the cached data at all, so there's no mutable state in the cache to get wrong. `CachedShortUrl` carries only `id`, `originalUrl`, `active`, and `expiresAt` — everything the redirect *decision* needs, nothing the redirect *write path* (the click count) touches.
- **`click_count` is incremented with a direct atomic `UPDATE` (`ShortUrlRepository.incrementClickCount`), replacing the previous load-entity-then-save approach**: a consequence of the cache design above rather than a goal on its own — once the redirect decision no longer loads the full entity (it reads `ShortUrlCache` instead), incrementing the count via load-modify-save would mean loading the entity a *second* time just to write one field. An atomic `UPDATE ... SET click_count = click_count + 1` avoids that reload entirely and, as a side effect, closes a latent race: the previous `entity.setClickCount(x + 1); save(entity)` pattern had no `@Version` field guarding it, so two concurrent redirects on the same popular short code could in principle read-modify-write over each other and lose an increment. The atomic form can't lose an update regardless of concurrency.
- **`ShortUrlCache` is an interface, not a method on `UrlShortenerServiceImpl` — the same self-invocation lesson from `ClickAnalyticsRecorder`, applied proactively this time**: Spring's `@Cacheable` proxy, like `@Async`'s, only intercepts calls arriving from *outside* the bean it's declared on. Putting `@Cacheable` directly on a method that `redirectToOriginalUrl` calls via `this.` within the same class would compile fine and silently never cache anything — no error, no warning, just a cache that never engages. Having already paid for this mistake once with `ClickAnalyticsRecorder` (see above), the interface split was done here from the start rather than discovered the same way again.
- **A 5-minute TTL on top of `@CacheEvict`, not instead of it**: `@CacheEvict` on `updateShortUrl` is what makes the cache correct in the common case — it fires immediately on the one write path that changes `active`/`expiresAt`. The TTL exists for what eviction can't reach: on a single instance, a hypothetical future write path that bypasses `updateShortUrl`; more concretely, if this ever runs on more than one instance, `@CacheEvict` only clears the cache on the instance that handled the write, and every *other* instance would keep serving its own stale entry until that instance's own TTL independently expires. Five minutes is a judgment call (like the rate limiter's 30 req/min), not a load-tested figure — short enough to bound real-world staleness, long enough that a genuinely popular link still gets meaningfully fewer database round-trips.

## 6. Execution Approach

The initial implementation proceeded layer-by-layer (entity → repository → service → controller → exception handling → tests). Everything since has shipped as one focused PR per task — one concern per branch, its own commit message stating intent and rationale, docs updated in the same PR as the code they describe. See [docs/SCENARIOS.md](SCENARIOS.md) for the task decomposition and AI-assistance trail behind specific examples of that process, with links to the actual merged PRs, and [docs/AI-TRACEABILITY.md](AI-TRACEABILITY.md) specifically for the moments engineer review changed the outcome — a bug caught by real testing, an output redirected after review, a scope decision the AI couldn't make alone.

## 7. What This Architecture Does Not Yet Address

These are scope gaps, not implementation bugs — tracked in full in the README's Known Limitations:
- A "top links" analytics view across all URLs (per-link stats are now implemented; cross-link aggregation is not)
- Reliability concerns: retry/circuit-breaking (health checks, rate limiting, async click recording, and the redirect-lookup cache are now implemented)
- The redirect cache is in-memory/per-instance, same limitation as rate limiting — `@CacheEvict` only clears the instance that handled the write; a shared cache (Redis) would close the multi-instance gap but is out of scope here (see Key Design Decisions and Known Limitations in the README)
- No integration test wires `UrlShortenerServiceImpl`'s `@CacheEvict` and `ShortUrlCache`'s `@Cacheable` together end to end in one live cache — each is verified independently (see Known Limitations in the README)
- List filtering (by `active`, expiry, creation date) — `GET /api/v1/urls` pages and sorts only; a "top links" view is `sort=clickCount,desc` and nothing more
- Delete is not race-free against an in-flight redirect's async analytics write (one orphan row possible — see Known Limitations in the README)
- `PATCH /api/v1/urls/{shortCode}` cannot clear an already-set `expiresAt` back to null (see Key Design Decisions and Known Limitations in the README)
- `ApiErrorController`'s `message` field is Spring Boot's own generic wording for the unmapped-path case (`handleNoResourceFound` intercepts that one with a purpose-written message instead) — but for security rejections, `ApiErrorController` is the actual, commonly-hit handler, not a rare fallback (see Known Limitations in the README)
- No integration test drives a real unauthenticated request through the live servlet `/error` forwarding pipeline end to end — `ApiErrorControllerTest` constructs the request/response itself (see Known Limitations in the README)
- AuthN/AuthZ now exists (`ROLE_ADMIN` via HTTP Basic on `/api/v1/urls/**`), but only as a single hardcoded admin — no multi-user accounts, no per-user ownership of links, no JWT/OAuth, no password rotation/lockout (see Known Limitations in the README)
- `RateLimitFilter` doesn't rate-limit failed-authentication attempts, since Spring Security's filter chain rejects them first (see Key Design Decisions and Known Limitations in the README)
- `/actuator/health` detail exposure has no access control — fine for local/prototype use, not for a shared deployment
- Rate limiting is in-memory/per-instance and keyed on the immediate TCP peer address — breaks down behind a load balancer or across multiple instances (see Known Limitations in the README); its memory footprint is bounded by a lazy sweep of expired windows, but that sweep is still per-instance
- Async click recording has no delivery guarantee — a failed or queue-rejected write is logged and dropped, not retried (see Known Limitations in the README)
- CI runs Checkstyle, the test suite, and SpotBugs, but no dependency-CVE scanning beyond Dependabot's alerts and no load testing
- The committed DB credential defaults are env-var-overridable, not eliminated — see Known Limitations in the README
