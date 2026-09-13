# URL Shortener App

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
| Reliability: per-IP rate limiting on `/api/v1/**` | ✅ Implemented |
| Reliability: async click recording (doesn't block/fail the redirect) | ✅ Implemented |
| Reliability: caching | ❌ Not implemented |
| List / update / delete / deactivate a short URL | ❌ Not implemented |
| Authentication / ownership of links | ❌ Not implemented |
| CI: build + test on every push/PR to `main` | ✅ Implemented |
| CI: dependency vulnerability alerts (Dependabot) | ✅ Implemented |
| CI: static analysis / linting | ❌ Not implemented |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for component design and control flow, and **Known Limitations** below for the full gap list against the target scope.

## Tech Stack

- Java 21, Spring Boot 3.4.1 (Web, Data JPA, Validation)
- MySQL 8 (via `mysql-connector-j`)
- springdoc-openapi (Swagger UI)
- JUnit 5 / Spring Boot Test

## Prerequisites

- JDK 21
- Maven 3.9+ (or use the wrapper if one is added)
- A running MySQL 8 instance

## Setup

1. **Create/configure the database.** The app auto-creates the schema (`spring.jpa.hibernate.ddl-auto=update`) and the database itself (`createDatabaseIfNotExist=true`), so you only need a reachable MySQL server and a user with privileges to create databases/tables.

2. **Configure connection settings.** [`src/main/resources/application.properties`](src/main/resources/application.properties) reads the datasource from environment variables, falling back to a local-dev-only default if unset:

   ```properties
   spring.datasource.url=${DB_URL:jdbc:mysql://localhost:3306/url_shortener?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}
   spring.datasource.username=${DB_USERNAME:root}
   spring.datasource.password=${DB_PASSWORD:admin1234}
   ```

   For local development against a MySQL instance with root/no-special-password, no setup is needed — the defaults just work. For anything else, set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` as environment variables rather than editing this file; never commit real credentials here (see Known Limitations for what this does and doesn't solve).

3. **Build and run:**

   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

   The service starts on `http://localhost:8080`.

4. **Explore the API:** Swagger UI is available at `http://localhost:8080/swagger-ui.html` (raw spec at `/v3/api-docs`).

5. **Check service health:** `http://localhost:8080/actuator/health` reports overall status plus a DB connectivity check; `/actuator/info` is exposed but currently empty (no build-info plugin configured).

## API Reference

| Method | Path | Description | Success | Failure |
|---|---|---|---|---|
| `POST` | `/api/v1/urls` | Create a short URL from `{ originalUrl, customCode?, expiresAt? }` (`expiresAt` must be a future timestamp) | `201 Created` | `400` invalid URL/payload/expiresAt, `409` short code exists |
| `GET` | `/api/v1/urls/{shortCode}` | Fetch metadata for a short code | `200 OK` | `404` not found |
| `GET` | `/api/v1/{shortCode}` | Redirect to the original URL, increments click count and records a click event | `302 Found` | `404` not found or inactive, `410` link expired |
| `GET` | `/api/v1/urls/{shortCode}/stats` | Click analytics: total clicks, first/last click timestamps, daily breakdown | `200 OK` | `404` not found |
| `GET` | `/actuator/health` | Service + DB health check | `200 OK` (`503` if a dependency is down) | — |

All `/api/v1/**` endpoints are also rate-limited per client IP (default: 30 requests/minute); an excess request gets `429 Too Many Requests` with the standard `ApiError` body.

## Testing

Run the test suite with:

```bash
mvn test
```

Current coverage (`src/test/java`):
- `UrlShortenerControllerTest` — endpoint-level request/response behavior, including the click-stats endpoint
- `UrlShortenerServiceImplTest` — short-code generation and collision retry, duplicate handling (including a save-time race for both generated and custom codes), redirect/click-count logic, expiration enforcement, and stats aggregation
- `UrlValidatorTest` — URL normalization/validation rules
- `ShortUrlRepositoryTest` / `ClickAnalyticsRepositoryTest` — persistence layer contracts
- `GlobalExceptionHandlerTest` — error response shape per exception type, including the new `410` expired-link mapping
- `FixedWindowRateLimiterTest` / `RateLimitFilterTest` — window limit/reset behavior (via an injectable `Clock`, not real sleeps) and the filter's pass-through/`429` responses per client IP
- `ClickAnalyticsRecorderTest` — verifies what gets saved (parsed browser name, referrer); run directly rather than through Spring, so it exercises the business logic, not the actual async dispatch (see Not yet covered)

**Not yet covered:** an actual concurrent-load test hitting a real MySQL instance to prove the retry-on-collision path under real contention (the race is unit-tested by simulating the exception, not reproduced with real concurrent threads/connections), the daily-breakdown JPQL query against a real MySQL instance (verified logically, not with an integration test against a live database), that `@Async` actually dispatches `recordClick` onto a different thread in a running Spring context (a `@SpringBootTest` with real thread-pool timing would be needed; skipped here as disproportionate for a prototype), load/performance testing.

## Continuous Integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs `mvn test` on every push and pull request against `main`, against a real MySQL 8 service container (not mocked) — this exercises `UrlShortenerAppApplicationTests`' full Spring context load (`@SpringBootTest`), which needs a live datasource to even start, using the `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` environment-variable overrides described in Setup. [`.github/dependabot.yml`](.github/dependabot.yml) opens weekly PRs for outdated/vulnerable Maven dependencies and GitHub Actions versions.

Not automated: static analysis / linting (e.g. Checkstyle, SpotBugs) and any deeper security scanning (e.g. dependency CVE scanning beyond what Dependabot alerts on) — see Known Limitations.

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
- **Rate limiting is in-memory, per-instance, and keyed on `request.getRemoteAddr()`** — fine for a single instance behind no proxy, but two problems if that changes: (1) running multiple instances means each has its own independent counter, so the effective limit multiplies with instance count; (2) behind a reverse proxy/load balancer, every request's remote address is the proxy's IP, not the real client's, so all traffic would share one bucket. A shared store (Redis) plus `X-Forwarded-For` handling would fix both — out of scope for this prototype.
- **No caching, no retry/circuit-breaker behavior.** (Actuator health/info endpoints, rate limiting, and async click recording are implemented — see Features.)
- **No management endpoints** — no list, update, delete, or deactivate operations; a link can never be turned off once created.
- **No auth/ownership model** — any client can create/read any short URL.
- **CI covers build + test only** — no static analysis/linting or dependency-CVE scanning beyond Dependabot's alerts is wired in yet (see Continuous Integration above).

## Project Status

This is an active, incremental build. Documentation of the engineering process (task decomposition, AI-assisted execution trail, scenario walkthroughs, and the final engineering summary) is being added alongside the code — see the `docs/` directory as it grows.
