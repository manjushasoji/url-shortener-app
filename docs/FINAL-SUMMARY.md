# Final Engineering Summary

A working URL shortener (Spring Boot 3 / Java 21 / MySQL), built incrementally on top of a pre-existing 3-endpoint core, with analytics, reliability, security, and caching added through an AI-assisted, engineer-reviewed workflow. This document is the wrap-up the assignment's Core Requirement #8 asks for — what was built, how, what was validated, and what's still open. It intentionally doesn't repeat detail that lives elsewhere; it points to it.

## What Was Built

- **Core:** create / resolve-metadata / redirect, short-code generation with collision retry, input validation.
- **Analytics:** per-link click stats (total, first/last click, daily breakdown) via a dedicated `click_analytics` event table, not just a counter.
- **Reliability:** Actuator health checks, per-IP rate limiting, async click recording (doesn't block the redirect), and an in-memory Caffeine cache on the redirect lookup.
- **Management:** `PATCH` to update `active`/`expiresAt`, consistent JSON error responses for unmapped paths.
- **Security:** `ROLE_ADMIN` (HTTP Basic) required for create/update/view-metadata/view-stats; the redirect endpoint stays public and lives in its own controller so that separation is structural, not just a matcher rule.

Full feature status, API reference, and setup steps: [README.md](../README.md). Gaps against the intended scope are tracked explicitly in its **Known Limitations** section rather than left implicit — e.g. no list/delete endpoints, no multi-user ownership, in-memory rate-limit/cache state that doesn't survive multiple instances.

## Architecture at a Glance

Layered Controller → Service → Repository, with two places that deliberately break the straight line: `RedirectController` is split out from the admin-gated controllers so public/private is enforced by controller boundary, not just security config; and cache-backed reads (`ShortUrlCache`) sit in front of the repository on the redirect's hot path, with writes (`click_count`) going straight through via an atomic `UPDATE` instead of load-modify-save. Full component diagram, data model, control flow, and the reasoning behind each non-obvious decision: [ARCHITECTURE.md](ARCHITECTURE.md).

## How the Assignment's Requirements Were Addressed

| Requirement | Where |
|---|---|
| Requirement Understanding | This document + [README.md](../README.md) Features/Known Limitations — scope was read from the assignment, not assumed |
| Task Decomposition | [SCENARIOS.md](SCENARIOS.md) — each feature broken into model → write path → read path (or equivalent) before code was written |
| Codebase Reasoning | [SCENARIOS.md](SCENARIOS.md) Brownfield section — fixes root-caused against actual existing behavior (e.g. 301 caching, `NoResourceFoundException` bypassing `/error`), not guessed |
| AI-Assisted Execution (traceability) | [AI-TRACEABILITY.md](AI-TRACEABILITY.md) — 11 logged instances where engineer review changed the outcome, each linked to its PR |
| Engineering Output Generation | ~20 merged PRs, one task per PR, each with tests and doc updates in the same PR |
| Validation and Risk Control | Testing section below + README **Known Limitations**, which names what's tested vs. asserted-by-inspection |
| Controlled Oversight | [AI-TRACEABILITY.md](AI-TRACEABILITY.md) — every non-trivial scope/architecture decision (what to build, which of 3 readings of an ambiguous request, the security shape) was engineer-directed, not AI-chosen |
| Final Engineering Summary | This document |

## Validation & Risk Control

Every merged PR includes unit/slice tests in the same change (JUnit 5, Mockito, `@WebMvcTest`/`@SpringJUnitConfig` where the behavior under test needs a real Spring proxy — e.g. `@Cacheable`, `@Async`, which a plain mock would silently bypass). What's **not** covered, by design, and named rather than hidden:

- No live-MySQL integration tests (no local DB in this environment) — collision retry, the daily-breakdown query, and the atomic click-increment are verified by mocked/simulated behavior, not real concurrent load.
- No end-to-end test of `@CacheEvict` actually clearing a live cache entry through the full service (unit-tested separately: the cache works, the eviction annotation is present — not wired together in one test).
- No load/performance testing, no full `spring-boot:run` HTTP round-trip test.

Every code change in this repo has been hand-verified by reading (no local Java/Maven toolchain in this working environment) — `./mvnw test` and `./mvnw spring-boot:run` should be run locally before relying on any of it. Full per-area breakdown: README **Testing**.

## Oversight Model

The engineer set scope and architecture; the AI executed within it and flagged what it couldn't decide alone. Concretely: ambiguous requests were clarified before code was written rather than guessed (e.g. "add a 4XX error for wrong url"); open-ended plans were cut down by explicit selection, not built in full (the reliability work); AI-proposed designs were checked against real system behavior and corrected when wrong, both in code (six defects caught via real testing or real stack traces) and in a design discussion caught before any code existed (the redirect-cache `expiresAt` staleness claim). Full log: [AI-TRACEABILITY.md](AI-TRACEABILITY.md).

## Trade-offs / What Would Change With More Time

- Redis (or similar) for rate-limit and cache state, so both survive multiple instances instead of being per-instance.
- List/delete endpoints and a real multi-user/ownership model instead of a single hardcoded admin.
- Live-database integration tests and basic load testing, run against a real MySQL instance.
- Static analysis/linting in CI (currently build+test only).

Each of these is already named as a specific, scoped gap — not a vague "more could be done" — in README **Known Limitations**, so the next engineer picking this up has a concrete starting list rather than a rediscovery task.
