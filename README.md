# URL Shortener App

[![CI](https://github.com/manjushasoji/url-shortener-app/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/manjushasoji/url-shortener-app/actions/workflows/ci.yml)

A REST service for creating, resolving, and redirecting shortened URLs, built with Spring Boot 3 and MySQL.

## Features

| Capability | Status |
|---|---|
| Create a short URL (auto-generated or custom code) | ✅ Implemented |
| Resolve short-code metadata (`GET /api/v1/urls/{shortCode}`) | ✅ Implemented |
| Redirect via short code with click counting | ✅ Implemented |
| Input validation (URL format, custom code format) | ✅ Implemented |
| Structured error responses | ✅ Implemented |
| OpenAPI/Swagger documentation | ✅ Implemented |
| Link expiration (optional `expiresAt` on create, enforced on redirect) | ✅ Implemented |
| Analytics: per-link click stats (total, first/last click, daily breakdown) | ✅ Implemented |
| Analytics: "top links" listing across all URLs | ❌ Not implemented yet |
| Health checks (Spring Boot Actuator: `/actuator/health`, `/actuator/info`) | ✅ Implemented |
| Reliability: per-IP rate limiting on the API and the public redirect | ✅ Implemented |
| Reliability: async click recording (doesn't block/fail the redirect) | ✅ Implemented |
| Reliability: caching the redirect lookup (Caffeine, in-memory) | ✅ Implemented |
| Update a short URL's `active`/`expiresAt` (`PATCH /api/v1/urls/{shortCode}`) | ✅ Implemented |
| Consistent JSON `ApiError` for unmapped paths (instead of the whitelabel page) | ✅ Implemented |
| List short URLs (paginated `GET /api/v1/urls`) / delete a short URL (`DELETE /api/v1/urls/{shortCode}`) | ✅ Implemented |
| Authentication: `ROLE_ADMIN` (HTTP Basic) required for create/update/view metadata/view stats | ✅ Implemented |
| Ownership of links (multi-user, per-user access) | ❌ Not implemented |
| CI: build + test on every push/PR to `main` | ✅ Implemented |
| CI: dependency vulnerability alerts (Dependabot) | ✅ Implemented |
| CI: static analysis / linting (Checkstyle + SpotBugs, fail the build) | ✅ Implemented |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for component design and control flow, and **Known Limitations** below for the full gap list against the target scope.

## Tech Stack

- Java 21, Spring Boot 3.4.1 (Web, Data JPA, Validation, Security, Cache)
- MySQL 8 (via `mysql-connector-j`)
- Caffeine (in-memory cache backing the redirect lookup)
- springdoc-openapi (Swagger UI)
- JUnit 5 / Spring Boot Test / spring-security-test

## Prerequisites

- JDK 21
- A running MySQL 8 instance
- No local Maven install needed — use the bundled wrapper (`./mvnw` on Linux/macOS, `mvnw.cmd` on Windows), which downloads and pins the exact Maven version (`3.9.16`) itself, matching what CI uses

## Setup

1. **Create/configure the database.** The app auto-creates the schema (`spring.jpa.hibernate.ddl-auto=update`) and the database itself (`createDatabaseIfNotExist=true`), so you only need a reachable MySQL server and a user with privileges to create databases/tables.

   The quickest way to get one is the bundled [`docker-compose.yml`](docker-compose.yml), which starts MySQL 8 with credentials matching the local-dev defaults below (so no further configuration is needed):

   ```bash
   docker compose up -d
   ```

   Stop it with `docker compose down` (add `-v` to also discard the data volume).

2. **Configure connection settings.** [`src/main/resources/application.properties`](src/main/resources/application.properties) reads the datasource from environment variables, falling back to a local-dev-only default if unset:

   ```properties
   spring.datasource.url=${DB_URL:jdbc:mysql://localhost:3306/url_shortener?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}
   spring.datasource.username=${DB_USERNAME:root}
   spring.datasource.password=${DB_PASSWORD:admin1234}
   ```

   For local development against a MySQL instance with root/no-special-password, no setup is needed — the defaults just work. For anything else, set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` as environment variables rather than editing this file; never commit real credentials here (see Known Limitations for what this does and doesn't solve).

3. **Configure the admin account.** The same pattern is used for the one admin user this API authenticates: `security.admin.username=${ADMIN_USERNAME:admin}` / `security.admin.password=${ADMIN_PASSWORD:admin123}` in `application.properties`. Override via `ADMIN_USERNAME`/`ADMIN_PASSWORD` env vars for anything beyond local dev.

4. **Build and run:**

   ```bash
   ./mvnw clean install
   ./mvnw spring-boot:run
   ```

   On Windows, use `mvnw.cmd` in place of `./mvnw`. The service starts on `http://localhost:8080`.

5. **Explore the API:** Swagger UI is available at `http://localhost:8080/swagger-ui.html` (raw spec at `/v3/api-docs`).

6. **Check service health:** `http://localhost:8080/actuator/health` reports overall status plus a DB connectivity check; `/actuator/info` is exposed but currently empty (no build-info plugin configured).

## Authentication

Every endpoint under `/api/v1/urls/**` (create, list, get metadata, update, delete, stats) requires HTTP Basic Auth with the admin account configured in Setup. `GET /{shortCode}` (the redirect, at the root so the short link is actually short) is deliberately public — a URL shortener has to work for anonymous visitors clicking the link, unlike managing the links themselves. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for why Basic Auth was chosen over JWT/OAuth.

```bash
# Admin-only — requires credentials
curl -u admin:admin123 -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://example.com"}'

# Public — no credentials needed
curl -i http://localhost:8080/abc12345
```

## API Reference

| Method | Path | Auth | Description | Success | Failure |
|---|---|---|---|---|---|
| `POST` | `/api/v1/urls` | `ROLE_ADMIN` | Create a short URL from `{ originalUrl, customCode?, expiresAt? }` (`expiresAt` must be a future timestamp) | `201 Created` | `400` invalid URL/payload/expiresAt/custom-code format, `401`/`403` auth, `409` short code already taken |
| `GET` | `/api/v1/urls` | `ROLE_ADMIN` | List short URLs, paginated: `page` (0-based, default 0), `size` (default 20, max 100 — larger values are clamped), `sort` (default `createdAt,desc`; any `ShortUrlResponse` field, e.g. `clickCount,desc`). Returns `{ content, page, size, totalElements, totalPages }` | `200 OK` | `401`/`403` auth |
| `GET` | `/api/v1/urls/{shortCode}` | `ROLE_ADMIN` | Fetch metadata for a short code | `200 OK` | `401`/`403` auth, `404` not found |
| `PATCH` | `/api/v1/urls/{shortCode}` | `ROLE_ADMIN` | Partially update `{ active?, expiresAt? }` — a `null`/omitted field is left unchanged, not cleared | `200 OK` | `400` no fields provided or `expiresAt` not in the future, `401`/`403` auth, `404` not found |
| `DELETE` | `/api/v1/urls/{shortCode}` | `ROLE_ADMIN` | Permanently remove a short URL and all of its `click_analytics` rows; the code stops redirecting immediately and becomes available for reuse | `204 No Content` | `401`/`403` auth, `404` not found |
| `GET` | `/{shortCode}` | Public | Redirect to the original URL, increments click count and records a click event. Only matches a path shaped like a short code (`[A-Za-z0-9-]{3,20}`), so `/favicon.ico` and the like get a normal `404` | `302 Found` | `404` not found or inactive, `410` link expired |
| `GET` | `/api/v1/urls/{shortCode}/stats` | `ROLE_ADMIN` | Click analytics: total clicks, first/last click timestamps, daily breakdown | `200 OK` | `401`/`403` auth, `404` not found |
| `GET` | `/actuator/health` | Public | Service + DB health check | `200 OK` (`503` if a dependency is down) | — |

All `/api/v1/**` endpoints and the public redirect are rate-limited per client IP (default: 30 requests/minute); an excess request gets `429 Too Many Requests` with the standard `ApiError` body. Actuator, Swagger UI, the OpenAPI spec, and `/error` are exempt (Swagger UI alone loads a dozen assets on open).

The redirect lookup (`GET /{shortCode}`) is cached in-memory (Caffeine, 5-minute TTL, 10,000-entry cap) — a repeat click on the same short code skips the database entirely for the active/expiry decision. `click_count` is never cached and always writes through on every redirect, cache hit or not (see the Key Design Decisions in ARCHITECTURE.md for why click counting and the redirect-decision cache had to be kept strictly separate).

Any path that doesn't match a route at all — a typo, a made-up endpoint, anywhere in the app, not just under `/api/v1` — returns the same `ApiError` JSON shape instead of an inconsistent response format. Two mechanisms are involved: an unmatched `GET`/`HEAD` request throws `NoResourceFoundException` through the normal controller-advice flow, so `GlobalExceptionHandler.handleNoResourceFound` catches it directly. Separately — and this turned out to be a much more common path than first thought — every Spring Security rejection (`401` missing/invalid credentials, `403` wrong role) is dispatched via `response.sendError()` straight to `/error`, which `ApiErrorController` handles; this is the *normal*, expected path for every unauthenticated request to an admin endpoint, not an edge case.

## Testing

Run the test suite with:

```bash
./mvnw test
```

To run the full quality gate CI applies (Checkstyle → compile → tests → SpotBugs), use `./mvnw verify` instead. Checkstyle rules are in [`config/checkstyle.xml`](config/checkstyle.xml) (a small hygiene set: no tabs/trailing whitespace/star or unused imports, braces required, no empty blocks, ≤160-char lines); SpotBugs runs at `Max` effort / `Medium` threshold with the project-wide suppressions — each with a stated reason — in [`config/spotbugs-exclude.xml`](config/spotbugs-exclude.xml). Both fail the build on any violation.

`ClickAnalyticsRecorder` is an interface (`ClickAnalyticsRecorderImpl` holds the actual `@Async` logic) — originally split out to keep the concrete class out of `UrlShortenerServiceImplTest`'s mocks, on the theory that Mockito's bytecode-instrumentation path was the problem. That theory turned out to be incomplete: the actual failure some contributors will see running `./mvnw test` locally is `Could not modify all classes [class java.lang.Object, ...]` caused by Byte Buddy (Mockito's bytecode library) not yet recognizing a JDK newer than it was built against (`Java N is not supported by the current version of Byte Buddy`) — this affects mocking *anything*, interface or class, on such a JDK, since even mocking an interface generates a proxy class via Byte Buddy. The actual fix is the `-Dnet.bytebuddy.experimental=true` Surefire flag below; the interface split is kept anyway since it's still a reasonable design (matches the `UrlShortenerService`/`Impl` pattern already used here), but don't rely on "mock interfaces, not classes" as a real fix for this specific error.

The `pom.xml` Surefire config sets `-Dnet.bytebuddy.experimental=true`, letting Byte Buddy attempt best-effort support for a JDK it hasn't officially validated against. This only matters for local runs on a very new JDK — CI pins JDK 21 via `actions/setup-java` and is unaffected either way.

Current coverage (`src/test/java`):
- `UrlShortenerControllerTest` — endpoint-level request/response behavior for the admin-gated endpoints (create, metadata, update, stats)
- `UrlShortenerServiceImplTest` — short-code generation and collision retry, duplicate handling (including a save-time race for both generated and custom codes), redirect/click-count logic, expiration enforcement, and stats aggregation
- `UrlValidatorTest` — URL normalization/validation rules
- `ShortUrlRepositoryTest` / `ClickAnalyticsRepositoryTest` — persistence layer contracts
- `GlobalExceptionHandlerTest` — error response shape per exception type, including `410` expired-link, `400` invalid-update, and `404` unmapped-path (`NoResourceFoundException`) mappings
- `UrlShortenerServiceImplTest` — list (page → envelope mapping, empty page) and delete (analytics rows removed *before* the link, in order; nothing deleted on a `404`)
- `UrlShortenerServiceImplTest` / `UrlShortenerControllerTest` — the new `PATCH` endpoint: deactivate, update `expiresAt`, update both, leaving an unspecified field unchanged, rejecting an empty update, `404` for a missing code, and `400` for a past `expiresAt` (exercised end-to-end through real Bean Validation via MockMvc)
- `FixedWindowRateLimiterTest` / `RateLimitFilterTest` — window limit/reset behavior (via an injectable `Clock`, not real sleeps), that expired client windows are purged from memory once per window while still-active ones survive the sweep with their counts intact, the filter's pass-through/`429` responses per client IP, that the root redirect is throttled, and that the exempt operational/docs prefixes pass through without consuming quota
- `ClickAnalyticsRecorderTest` — verifies what gets saved (parsed browser name, referrer); run directly rather than through Spring, so it exercises the business logic, not the actual async dispatch (see Not yet covered)
- `ApiErrorControllerTest` — verifies the `/error` handler maps `HttpServletResponse.getStatus()` plus the error-attributes' `path`/`message` into the same `ApiError` shape as every other endpoint, including a dedicated regression test for the security-401-defaulted-to-500 bug and sensible defaults when an attribute or the response status is missing
- `SecurityConfigTest` — the password encoder round-trips, and the in-memory admin user is registered with the configured username, a correctly-encoded password, and `ROLE_ADMIN`
- `UrlShortenerControllerTest` — every admin endpoint runs as `@WithMockUser(roles = "ADMIN")` by default, plus two dedicated negative tests: `401` with no authentication (`@WithAnonymousUser`), `403` authenticated as a non-admin role
- `RedirectControllerTest` — goes through real MockMvc dispatch (not manual controller instantiation, unlike the old version of this test) with no mock authentication at all; a passing `302`/`404` here is itself proof the endpoint is genuinely public, since a wrong `SecurityConfig` matcher would surface as `401`
- `ShortUrlCacheCachingTest` — a real Spring context (`CacheConfig`'s actual `CaffeineCacheManager`, a genuine AOP proxy around `ShortUrlCacheImpl`), not plain Mockito: repeated lookups for the same code hit the repository once, not-found results are never cached, and different codes cache independently. A pure-Mockito unit test would never exercise `@Cacheable`'s proxy at all — the same self-invocation trap already hit once with `@Async` (see `ClickAnalyticsRecorder`)
- `UrlShortenerServiceImplTest` — redirect tests rewritten around `ShortUrlCache`/`CachedShortUrl` instead of the raw entity, plus a new inactive-link case and a case verifying a cache-thrown `ResourceNotFoundException` propagates correctly
- `ShortUrlRepositoryTest` — the new `incrementClickCount` mock interaction

**Not yet covered:** an actual concurrent-load test hitting a real MySQL instance to prove the retry-on-collision path under real contention (the race is unit-tested by simulating the exception, not reproduced with real concurrent threads/connections), the daily-breakdown JPQL query against a real MySQL instance (verified logically, not with an integration test against a live database), that `@Async` actually dispatches `recordClick` onto a different thread in a running Spring context (a `@SpringBootTest` with real thread-pool timing would be needed; skipped here as disproportionate for a prototype), that a real request — either a genuinely unmapped path, or a real unauthenticated request to an admin endpoint — actually reaches `ApiErrorController` through the full servlet container `/error` forwarding pipeline and produces the expected status (`ApiErrorControllerTest` calls the controller method directly with a request/response it constructs itself, not through a live `DispatcherServlet`; this is exactly the kind of gap that let the 401-becomes-500 bug ship in the first place), an end-to-end Basic Auth round-trip with real (not mocked) `HttpSecurity` wiring against a live server — the `@WebMvcTest` slices import `SecurityConfig` directly and verify authorization decisions, but not the full `spring-boot:run` startup path, that `@CacheEvict` on `updateShortUrl` actually clears a live cache entry end to end (verified by inspection and by `ShortUrlCacheCachingTest` proving the cache itself works, but not the two wired together in one test — that would need `UrlShortenerServiceImpl` and its other collaborators in the same cache-enabled context), `incrementClickCount`'s atomic `UPDATE` against a real MySQL instance (same class of gap as the daily-breakdown query — mock-verified only), load/performance testing.

## Continuous Integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs `./mvnw -B verify` — Checkstyle, compile, the test suite, then SpotBugs — on every push and pull request against `main` (the wrapper, so CI uses the exact same Maven version as local dev), against a real MySQL 8 service container (not mocked) — this exercises `UrlShortenerAppApplicationTests`' full Spring context load (`@SpringBootTest`), which needs a live datasource to even start, using the `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` environment-variable overrides described in Setup. [`.github/dependabot.yml`](.github/dependabot.yml) opens weekly PRs for outdated/vulnerable Maven dependencies and GitHub Actions versions.

Not automated: dependency CVE scanning beyond what Dependabot alerts on, and any load/performance testing — see Known Limitations.

## Known Limitations

These are open gaps against the intended scope (core APIs + analytics + reliability), tracked here rather than left implicit:

- **DB credentials are environment-variable-overridable, but a placeholder default is still committed** (`DB_PASSWORD:admin1234` in `application.properties`) — this fixes the original problem (nothing forces anyone to use or extend that value; any real environment sets `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` and the committed default is never touched), but a literal fallback string is still readable in source control. A stricter version would have no default at all and fail fast if the env vars are unset — left as a default here so local dev and CI stay zero/low-config; worth reconsidering before this repo is anything other than a prototype.
- **`/actuator/health` shows full dependency details with no authentication** (`management.endpoint.health.show-details=always`) — acceptable for local/prototype use, but should be restricted (e.g. `when-authorized`, or gated behind network/auth controls) before any shared deployment, since it can reveal internal DB connectivity details to any caller.
- **Short-code generation retries a fixed number of times (5) on collision, not indefinitely** — with an 8-character, 62-character-alphabet code space, collision odds are astronomically low, so this is a safety net rather than an expected path; if it's ever exhausted, `createShortUrl` fails with a clear `409` rather than looping forever.
- **Async click recording has no delivery guarantee** — `ClickAnalyticsRecorder.recordClick` runs on a bounded background thread pool (`@Async`, core 2 / max 8 / queue 500); if the DB write fails, it's only logged (`AsyncConfig`'s uncaught-exception handler), not retried, and the click is silently dropped from analytics. If the queue fills up under sustained load, further async submissions are rejected (default `AbortPolicy`) and would also be logged-and-dropped rather than blocking the redirect. Acceptable since analytics accuracy is explicitly secondary to redirect availability here, but worth knowing before relying on click counts being exact.
- **No "top links" analytics view** — per-link stats (`/api/v1/urls/{shortCode}/stats`) are implemented, but there's no endpoint yet to list/sort all URLs by click volume.
- **Daily-breakdown query untested against real MySQL** — the `CAST(... AS date)` JPQL aggregation in `ClickAnalyticsRepository` is covered by mock-based unit tests only; it hasn't been run against a live database yet.
- **`referrer` will often be null** — it's populated from the `Referer` HTTP header, which browsers only send when navigation originates from a link on another page. Direct/typed navigation, HTTPS→HTTP downgrades, and privacy-focused browsers/extensions all omit it. This is expected client behavior, not a bug — treat `referrer` as best-effort, not guaranteed data.
- **`user_agent` stores a parsed browser name, not the raw header** — `UserAgentParser.extractBrowserName` reduces the raw `User-Agent` string down to one of `Chrome`, `Firefox`, `Safari`, `Edge`, `Opera`, `Internet Explorer`, `Other` (unrecognized client), or `Unknown` (header missing). It uses simple substring checks in a specific order (checking Edge/Opera before Chrome, since their UA strings also contain "Chrome") rather than a full parsing library, so unusual or future browser UA formats may fall into `Other`. The raw header itself is not retained.
- **Rate limiting is in-memory, per-instance, and keyed on `request.getRemoteAddr()`** — fine for a single instance behind no proxy, but two problems if that changes: (1) running multiple instances means each has its own independent counter, so the effective limit multiplies with instance count; (2) behind a reverse proxy/load balancer, every request's remote address is the proxy's IP, not the real client's, so all traffic would share one bucket. A shared store (Redis) plus `X-Forwarded-For` handling would fix both — out of scope for this prototype. Memory use is bounded, though: expired client windows are swept out lazily (at most once per window duration, on the next request), so the map holds only clients seen within roughly the last two windows rather than every IP ever seen.
- **The redirect cache is in-memory and per-instance, same as the rate limiter** — `@CacheEvict` on `updateShortUrl` only clears the cache on whichever instance handled that request; if this ever runs on more than one instance, another instance could keep serving a just-deactivated link from its own stale cache entry until its 5-minute TTL expires. The TTL is deliberately short specifically to bound that gap; a shared cache (Redis) would close it entirely but is out of scope for this prototype.
- **The cache assumes `active`/`expiresAt` only ever change through `updateShortUrl`** — true today (it's the only write path for either field), but `@CacheEvict` has no way to know if that assumption stops holding; a future write path that bypasses this method would leave the cache silently stale until TTL.
- **No retry/circuit-breaker behavior.** (Actuator health/info endpoints, rate limiting, async click recording, and caching are implemented — see Features.)
- **`PATCH /api/v1/urls/{shortCode}` can't clear an already-set `expiresAt`** — a `null`/omitted `expiresAt` in the request means "leave unchanged," so once a link has an expiration, this endpoint has no way to remove it again (Jackson can't distinguish an omitted field from an explicit `null` in a record without extra tooling, so one convention had to be picked; "unchanged" matches typical PATCH semantics). Would need a dedicated action (e.g. a query param or separate endpoint) to support clearing it.
- **Delete is not race-free against an in-flight redirect** — `DELETE` removes the `short_url` row and its `click_analytics` rows in one transaction and evicts the cache, but a redirect that already passed its lookup can still enqueue an async `recordClick`, which then inserts an analytics row for an id that no longer exists (`click_analytics` has no FK, so the insert succeeds). Harmless — nothing reads analytics by a deleted id — but it means "delete removes all analytics" is true only up to that narrow window.
- **The list endpoint has no filtering** — `GET /api/v1/urls` pages and sorts, but can't filter by `active`, expiry, or creation date, and there's still no "top links" view beyond `sort=clickCount,desc`.
- **Single hardcoded admin account, not a user store** — `SecurityConfig` registers exactly one `InMemoryUserDetailsManager` user from `ADMIN_USERNAME`/`ADMIN_PASSWORD` (same env-var-with-local-dev-default pattern as the DB credentials, and the same residual caveat: a placeholder default is still committed). There's no way to add a second admin, no per-user accounts, no password rotation, and no account lockout after repeated failed attempts — appropriate for "prove role-gating works," not for anything with more than one operator.
- **Basic Auth sends credentials on every request** — base64-encoded, not encrypted; safe only over HTTPS. This app doesn't terminate or enforce TLS itself (that's normally a reverse-proxy/load-balancer concern), so there's no local safeguard against Basic Auth credentials going out in the clear if someone hits the app directly over plain HTTP outside local dev.
- **`RateLimitFilter` doesn't see requests Spring Security rejects** — Spring Security's filter chain runs before `RateLimitFilter` in the servlet filter order (Security's default order is well ahead of the `1` this custom filter registers at), so a failed-auth request to an admin endpoint is rejected with `401`/`403` before ever reaching the rate limiter. Practically: repeated bad-credential attempts against `/api/v1/urls/**` aren't counted against that IP's quota, and Spring Security itself has no built-in lockout either — brute-forcing the admin password isn't rate-limited by anything in this app today.
- **No auth/ownership model beyond a single admin role** — the admin can create/read/update *any* short URL; there's no concept of "this link belongs to this user."
- **CI covers Checkstyle + build + test + SpotBugs, but no CVE scanning or load testing** — dependency vulnerability coverage is only what Dependabot alerts on (no OWASP dependency-check or similar in the pipeline), and there is no performance baseline for the redirect path (see Continuous Integration above).
- **`ApiErrorController` is not a rarely-used fallback — it's the normal path for every Spring Security rejection.** Earlier documentation here claimed `/error` was rarely reached, based on the unmapped-path case alone; adding admin authentication changed that; a `401`/`403` from `SecurityConfig` is dispatched to `/error` via `response.sendError()` for every unauthenticated/wrong-role request, so this controller now runs on essentially every failed-auth call, not just an edge case. A real bug surfaced from this: the controller originally derived its response status by parsing a `"status"` key out of Spring Boot's `DefaultErrorAttributes` map, which is populated reliably for the `NoResourceFoundException` path but was **not** reliably populated for a security-triggered `sendError()` — a `401` rejection came back as `500 Internal Server Error` with the body `{"message": "Unauthorized", ...}` (the message came through, the status silently didn't). Fixed by reading the status directly off `HttpServletResponse.getStatus()` instead, which `sendError()`/`setStatus()` set synchronously regardless of which mechanism triggered the error — see the code comment in `ApiErrorController` and the Key Design Decision in ARCHITECTURE.md for the full reasoning on why this is now reliable for every path that reaches `/error` in this app.

## Project Status

This is an active, incremental build. See [docs/SCENARIOS.md](docs/SCENARIOS.md) for the greenfield/brownfield/ambiguous scenario walkthroughs (decomposition, execution, validation, with links to the actual merged PRs), [docs/AI-TRACEABILITY.md](docs/AI-TRACEABILITY.md) for the specific instances where engineer review caught a defect, redirected an approach, or made a scope call the AI couldn't make alone, and [docs/FINAL-SUMMARY.md](docs/FINAL-SUMMARY.md) for the wrap-up: what was built, how the assignment's requirements were addressed, and what would change with more time.
