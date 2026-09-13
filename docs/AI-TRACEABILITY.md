# AI Traceability & Human Oversight Log

The assignment's Core Requirement #4 (AI-Assisted Execution) asks for "traceability (generated/edited/rejected with rationale)... human sign-off for high-impact changes... explicit engineer ownership of correctness." Requirement #7 (Controlled Oversight) asks that "Engineer leads execution and approves all outputs; AI assists within tasks." This document is that trail — every entry below is a real moment from this project's actual session history where the engineer reviewed AI-generated output and either caught a defect, redirected an approach, or made a scope decision the AI couldn't make alone. Nothing here is reconstructed after the fact; every entry links to the commit/PR where it happened.

This is not a list of "AI wrote code, human clicked merge." It's specifically the moments where review changed the outcome.

## At a Glance

| # | What AI produced | What the engineer caught/directed | Result |
|---|---|---|---|
| 1 | Redirect endpoint returning `301 Moved Permanently` | Real browser testing: clicks stopped incrementing after the first one per browser | Switched to `302 Found`; root cause (browser caches 301s) documented |
| 2 | `user_agent` storing the raw `User-Agent` header | Reviewed the stored data, found it confusing/unusable as-is, directed a specific change | Added `UserAgentParser`, storing a parsed browser name only |
| 3 | `ApiErrorController` (`/error`-based) as the fix for unmapped paths | Real request to an unmapped path returned `500`, not `404` | Root-caused to `NoResourceFoundException` bypassing `/error` entirely; added a direct handler |
| 4 | Same area, after admin auth was added | Real unauthenticated request returned `500` with a body describing `401` | Root-caused to a status-parsing bug in `ApiErrorController`; fixed to read the real response status |
| 5 | A new unit test with an unused Mockito stub | `mvn test` run locally, `UnnecessaryStubbingException` reported | Fixed the test to assert on the previously-unused stub |
| 6 | A Surefire JVM flag as the fix for a Mockito failure | Reported back as not fixing it; asked for (and received) the full stack trace | Original hypothesis was wrong; root cause was a JDK/Byte Buddy version mismatch; fixed with the correct flag |
| 7 | An open-ended "reliability" plan with 5 candidate sub-tasks | Asked which to build; engineer selected 3 of 5, explicitly deferring caching | Only the selected scope was built |
| 8 | A literal, ambiguous request ("4XX error for wrong url") | Asked which of 3 readings was meant before writing code | Implementation matched the selected reading, not a guess |
| 9 | A request to "add security" with unspecified shape | Directed specific architecture: split the redirect endpoint out, admin-only elsewhere | Built exactly that shape, not an AI-chosen alternative |
| 10 | Engineer's own `mvnw` wrapper files, committed directly | AI reviewed them before building on top, found a real bug (missing executable bit) | Fixed and wired into CI/docs, credited as the engineer's addition |
| 11 | A design explanation claiming a cached `expiresAt` could let an expired link keep redirecting | Precisely disproved with the actual field semantics, before any code was written | Correction identified the real risk (`active`, not `expiresAt`); implementation and `@CacheEvict` placement built around the corrected understanding |

## Detail

### 1. Redirect caching bug (301 → 302)

**Generated:** The redirect endpoint (`GET /api/v1/{shortCode}`) returned `301 Moved Permanently`.

**Caught:** The engineer tested the running app directly — clicking a short link twice from the same browser only incremented `click_count` once; a fresh browser incremented it again. That's not a symptom the AI could have found through code review alone; it required actually using the deployed behavior.

**Correction:** Diagnosed that `301` is spec-cacheable — a browser resolves the redirect from its own cache after the first click, never re-hitting the server. Switched to `302 Found`. [fix/redirect-302-for-click-counting (PR #3)](https://github.com/manjushasoji/url-shortener-app/pull/3).

### 2. `user_agent` reduced to a parsed browser name

**Generated:** The click-analytics feature stored the raw `User-Agent` header string.

**Caught:** The engineer asked what the field represented and why a single value looked like it listed multiple browsers (a legitimate UA-string artifact, explained at the time), then explicitly directed: *"I want just the actual browser that is used to be captured there, change accordingly."* This is output-review-and-redirect, not a bug — the original behavior worked as built, it just wasn't what was wanted once seen in practice.

**Correction:** Added `UserAgentParser.extractBrowserName`, reducing the header to one of `Chrome`/`Firefox`/`Safari`/`Edge`/`Opera`/`Internet Explorer`/`Other`/`Unknown`. [feature/parse-browser-name-from-user-agent (PR #4)](https://github.com/manjushasoji/url-shortener-app/pull/4).

### 3 & 4. Two related error-handling bugs, both caught by real requests

**Generated (first):** `ApiErrorController`, mapped to `/error`, as the mechanism for turning an unmapped path into a proper `404`.

**Caught:** A real request to `/api/v2/wcom` came back `500`, not `404`. Root cause: Spring Framework 6.1 routes an unmatched `GET`/`HEAD` through `NoResourceFoundException` via the normal controller-advice chain, never reaching `/error` at all — so `ApiErrorController` was solving a problem that, for this specific case, didn't reach it. **Correction:** added `GlobalExceptionHandler.handleNoResourceFound`. [feature/consistent-404-for-unmapped-paths (PR #17)](https://github.com/manjushasoji/url-shortener-app/pull/17) / [fix/handle-no-resource-found-exception (PR #18)](https://github.com/manjushasoji/url-shortener-app/pull/18).

**Generated (second, after admin auth landed):** The same `ApiErrorController`, now also reachable for Security rejections.

**Caught:** A real unauthenticated request to a metadata endpoint returned `500` with body `{"message": "Unauthorized", "status": 500, "path": "/error"}` — a `401` masquerading as a `500`. Root cause: the controller parsed `"status"` out of Spring Boot's `DefaultErrorAttributes` map, which isn't reliably populated when Security rejects a request via `response.sendError()` directly rather than throwing. **Correction:** read the status from `HttpServletResponse.getStatus()` instead, which is set synchronously regardless of which mechanism triggered the error. [fix/apierrorcontroller-status-from-response (PR #21)](https://github.com/manjushasoji/url-shortener-app/pull/21). Both PR #17's and #18's own documentation were corrected afterward to stop calling `ApiErrorController` "rarely reached" — it turned out to be the normal path for every failed-auth request.

### 5 & 6. Test and build failures diagnosed from what the engineer actually ran

**Generated:** A new test (`ClickAnalyticsRepositoryTest.findDailyClickCounts_shouldReturnOrderedBreakdown`) that stubbed `getClickDate()` but only asserted on `getClickCount()`.

**Caught:** Running `mvn test` locally surfaced `UnnecessaryStubbingException` — Mockito's strict-stubbing check flags a configured-but-unused stub as an error. **Correction:** the test now asserts on the date too, which also makes it actually verify what its name claims.

**Generated:** A `@Mock private ClickAnalyticsRecorder` field triggered `MockitoException: Could not modify all classes [class java.lang.Object, ...]`. First fix attempt: a Surefire JVM flag (`-Djdk.attach.allowAttachSelf=true`), based on the hypothesis that Mockito's inline mock maker needed it for concrete-class mocking.

**Caught:** Reported back as still failing after merge. Rather than guess a second flag, the engineer was asked for — and provided — the complete stack trace, which revealed the real cause buried at the bottom: Byte Buddy not yet supporting a JDK newer than it was built against. The original self-attach hypothesis was simply wrong.

**Correction:** Reverted the ineffective flag, split `ClickAnalyticsRecorder` into an interface (a reasonable change on its own merits, but *not* the fix), and applied the actual fix (`-Dnet.bytebuddy.experimental=true`). The earlier commit's own documentation was corrected to say the first attempt was wrong, rather than left standing as if it had worked. [fix/click-analytics-recorder-interface (PR #15)](https://github.com/manjushasoji/url-shortener-app/pull/15).

### 7. Scope decision: reliability work

**Proposed:** An open-ended plan covering five candidate reliability improvements (health checks, expiration/race-condition fixes, caching, rate limiting, async click recording), each with trade-offs laid out.

**Directed:** The engineer selected three ("Health checks + correctness fixes," "Rate limiting," "Async click recording") via a structured choice, explicitly not selecting caching.

**Result:** Only the selected scope was built ([PR #5](https://github.com/manjushasoji/url-shortener-app/pull/5), [PR #6](https://github.com/manjushasoji/url-shortener-app/pull/6), [PR #7](https://github.com/manjushasoji/url-shortener-app/pull/7)); caching remains an explicit, named gap in README's Known Limitations rather than something built without being asked for.

### 8. Ambiguity resolved before writing code

**Request as given:** "add a 4XX error for wrong url" — genuinely ambiguous in this codebase, with at least three defensible readings (invalid `originalUrl` on create, a malformed short code in the path, or a path matching no route at all).

**Directed:** Asked which reading was intended, with what each would concretely involve, before touching any code. The engineer selected "unknown/unmapped path."

**Result:** The two PRs referenced in items 3–4 above, built against the selected interpretation — not a guess that might have solved the wrong problem.

### 9. Architecture directed, not chosen by the AI

**Request as given:** "keep 'redirect' endpoint in a separate controller and add security to other endpoints, like only someone with admin role can add, update, view metadata and view stats."

This specified the shape of the solution directly (which endpoint stays public, which requires which role, and that they should be physically separated) — the AI's job here was faithful implementation and identifying the design decisions *within* that shape (Basic Auth vs. JWT, matcher ordering, in-memory vs. persisted users), not choosing the shape itself. [feature/admin-role-security (PR #20)](https://github.com/manjushasoji/url-shortener-app/pull/20).

### 10. Reviewing the engineer's own work, not just AI output

**Engineer-authored:** `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties`, committed directly to `main` (confirmed via `git log` — commit `a3e6d15 "mvn files"`, not touched by any AI-authored PR before that point).

**AI-reviewed:** Before building anything on top, checked `git ls-files -s mvnw` and found it committed as mode `100644` — not executable — which would fail with "Permission denied" running `./mvnw` on Linux/macOS, including this project's own GitHub Actions runner. This wasn't a hypothetical: it's the kind of bug that only surfaces at the exact moment someone tries to run the script.

**Correction:** Fixed the executable bit via `git update-index --chmod=+x`, and wired `ci.yml`/README to actually use the wrapper (previously it existed but nothing invoked it, including CI). [build/add-maven-wrapper (PR #22)](https://github.com/manjushasoji/url-shortener-app/pull/22). Oversight in this project ran in both directions — this is the one instance where the AI caught a defect in engineer-authored input rather than the other way around.

### 11. Caching design: a flawed staleness claim, caught before any code was written

**Generated:** While explaining the difficulty of adding a redirect-lookup cache, the AI claimed that caching the `expiresAt` timestamp would let an already-expired link keep redirecting: *"A redirect request at 2:01pm would hit the cache, get the stale 'still fine' snapshot, and incorrectly redirect a link that should now be 410 Gone."*

**Caught:** The engineer asked "why?" and then, on seeing the reasoning spelled out, disproved it directly with the actual field semantics: *"At 2.01 the expiresAt column value is still 2.00pm so it will not redirect. rt?"* This was correct — the stored `expiresAt` value doesn't change at 2:00pm just because that time has passed; only a live comparison against `now()` can determine expiry, and that comparison runs fresh on every request regardless of whether the surrounding data came from cache.

**Correction:** Acknowledged the error and corrected the design: caching the raw `expiresAt` timestamp is safe, because `redirectToOriginalUrl` re-evaluates it against `LocalDateTime.now()` on every call — caching never freezes that comparison. The actual risk is the `active` flag, which (unlike `expiresAt`) only changes value via an explicit write, so a cached "active" snapshot really can go stale. The engineer then directed the fix for that real risk: *"Only Patch can update active flag, so on Patch calling cacheEvit will solve the problem rt?"* — confirmed correct, with two caveats surfaced back: `@CacheEvict` only clears the local instance's cache (the same per-instance-state limitation already accepted for the rate limiter), and the fix assumes PATCH remains the only write path for `active`/`expiresAt`.

**Result:** `CachedShortUrl` caches the raw `expiresAt` (per the corrected understanding) and deliberately excludes `click_count`; `updateShortUrl` carries `@CacheEvict(value = "shortUrls", key = "#shortCode")` as the mechanism relied on for `active`. Unlike items 1–6 above, nothing here was ever committed in a wrong state — the flaw was caught in the design conversation itself, before the first line of caching code was written. [feature/add-redirect-cache (PR #26)](https://github.com/manjushasoji/url-shortener-app/pull/26).

## What This Log Doesn't Cover

Every ordinary "generate a feature, write its tests, document its trade-offs" PR in this project's history — there are roughly twenty of them, linked from [SCENARIOS.md](SCENARIOS.md) and cited throughout [README.md](../README.md) and [ARCHITECTURE.md](ARCHITECTURE.md) — is AI-assisted execution too, but it's not *traceability* in the sense this document is about: those are cases where review didn't change the outcome, because there was nothing to catch. This document is specifically the subset where it did.
