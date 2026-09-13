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
| Link expiration enforcement | ⚠️ Schema field exists (`expires_at`), not enforced yet |
| Analytics: per-link click stats (total, first/last click, daily breakdown) | ✅ Implemented |
| Analytics: "top links" listing across all URLs | ❌ Not implemented yet |
| Reliability features (rate limiting, caching, health checks) | ❌ Not implemented |
| List / update / delete / deactivate a short URL | ❌ Not implemented |
| Authentication / ownership of links | ❌ Not implemented |

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

2. **Configure connection settings.** Current settings live in [`src/main/resources/application.properties`](src/main/resources/application.properties):

   ```properties
   spring.datasource.url=jdbc:mysql://localhost:3306/url_shortener?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
   spring.datasource.username=root
   spring.datasource.password=admin1234
   ```

   > ⚠️ **Known issue:** these are hardcoded credentials committed to source control. Override them for your own environment via environment variables or a local `application-local.properties` (see Known Limitations) rather than editing the committed file with real credentials.

3. **Build and run:**

   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

   The service starts on `http://localhost:8080`.

4. **Explore the API:** Swagger UI is available at `http://localhost:8080/swagger-ui.html` (raw spec at `/v3/api-docs`).

## API Reference

| Method | Path | Description | Success | Failure |
|---|---|---|---|---|
| `POST` | `/api/v1/urls` | Create a short URL from `{ originalUrl, customCode? }` | `201 Created` | `400` invalid URL/payload, `409` short code exists |
| `GET` | `/api/v1/urls/{shortCode}` | Fetch metadata for a short code | `200 OK` | `404` not found |
| `GET` | `/api/v1/{shortCode}` | Redirect to the original URL, increments click count and records a click event | `302 Found` | `404` not found or inactive |
| `GET` | `/api/v1/urls/{shortCode}/stats` | Click analytics: total clicks, first/last click timestamps, daily breakdown | `200 OK` | `404` not found |

## Testing

Run the test suite with:

```bash
mvn test
```

Current coverage (`src/test/java`):
- `UrlShortenerControllerTest` — endpoint-level request/response behavior, including the click-stats endpoint
- `UrlShortenerServiceImplTest` — short-code generation, duplicate handling, redirect/click-count logic, click-event recording, and stats aggregation
- `UrlValidatorTest` — URL normalization/validation rules
- `ShortUrlRepositoryTest` / `ClickAnalyticsRepositoryTest` — persistence layer contracts
- `GlobalExceptionHandlerTest` — error response shape per exception type

**Not yet covered:** concurrency/race conditions on short-code creation, expiration behavior (since it isn't implemented), the daily-breakdown JPQL query against a real MySQL instance (verified logically, not with an integration test against a live database), load/performance testing.

## Known Limitations

These are open gaps against the intended scope (core APIs + analytics + reliability), tracked here rather than left implicit:

- **Hardcoded DB credentials** committed in `application.properties` — should move to environment variables/secrets before any shared or production use.
- **`expires_at` is not enforced** — the column exists on `ShortUrl` but `redirectToOriginalUrl` never checks it, so expired links still redirect.
- **Race condition on short-code creation** — `existsByShortCode` is checked, then the entity is saved, with no unique-constraint-violation handling in between; concurrent requests could still collide (the DB has a unique index as a backstop, but the app doesn't catch/retry on that constraint violation).
- **Click recording is synchronous** — each redirect writes a `click_analytics` row in the same request/transaction as the redirect itself, adding a write to the hot path. Planned fix: move this to an async write once the reliability work lands, so analytics recording can't slow down or fail a redirect.
- **No "top links" analytics view** — per-link stats (`/api/v1/urls/{shortCode}/stats`) are implemented, but there's no endpoint yet to list/sort all URLs by click volume.
- **Daily-breakdown query untested against real MySQL** — the `CAST(... AS date)` JPQL aggregation in `ClickAnalyticsRepository` is covered by mock-based unit tests only; it hasn't been run against a live database yet.
- **`referrer` will often be null** — it's populated from the `Referer` HTTP header, which browsers only send when navigation originates from a link on another page. Direct/typed navigation, HTTPS→HTTP downgrades, and privacy-focused browsers/extensions all omit it. This is expected client behavior, not a bug — treat `referrer` as best-effort, not guaranteed data.
- **`user_agent` stores a parsed browser name, not the raw header** — `UserAgentParser.extractBrowserName` reduces the raw `User-Agent` string down to one of `Chrome`, `Firefox`, `Safari`, `Edge`, `Opera`, `Internet Explorer`, `Other` (unrecognized client), or `Unknown` (header missing). It uses simple substring checks in a specific order (checking Edge/Opera before Chrome, since their UA strings also contain "Chrome") rather than a full parsing library, so unusual or future browser UA formats may fall into `Other`. The raw header itself is not retained.
- **No reliability hardening** — no rate limiting, no caching, no Actuator health/readiness endpoints, no retry/circuit-breaker behavior.
- **No management endpoints** — no list, update, delete, or deactivate operations; a link can never be turned off once created.
- **No auth/ownership model** — any client can create/read any short URL.
- **No CI pipeline** — tests, linting, and security scanning are not automated.

## Project Status

This is an active, incremental build. Documentation of the engineering process (task decomposition, AI-assisted execution trail, scenario walkthroughs, and the final engineering summary) is being added alongside the code — see the `docs/` directory as it grows.
