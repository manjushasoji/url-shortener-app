# Scenarios

The assignment asks for three kinds of work — greenfield, brownfield, and ambiguous — each showing decomposition, execution, and validation. Rather than construct hypothetical examples, this document points at real instances of each from this project's actual history: every file, decision, and trade-off below happened, is in [README.md](../README.md) / [ARCHITECTURE.md](ARCHITECTURE.md) as a documented Known Limitation or Key Design Decision, and is linked to its merged PR for the full diff.

This doc doesn't repeat what those two already cover in depth (architecture, trade-offs, setup). It's the narrative layer: *why* each task was approached the way it was, and how the AI-assisted execution loop actually worked task to task.

## Greenfield: The Core URL Shortener

**The original three endpoints.** Before any of this session's AI-assisted work began, the repository already contained a minimal but complete URL shortener: three commits ("first commit," "Initial commit," "Test cases") establishing the foundation everything else in this document builds on. Listed here for completeness, since they *are* the greenfield core of the system, even though this session's own decomposition/execution/validation loop wasn't applied to building them — they were the starting point, not something reconstructed from scratch:

- **Create a short URL** — `POST /api/v1/urls`. Accepts an absolute `http(s)` URL, either auto-generates an 8-character short code (`SecureRandom` over a 62-character alphabet) or accepts a caller-supplied custom code, and persists a new `ShortUrl` row.
- **Redirect** — `GET /api/v1/{shortCode}`. Resolves a short code to its original URL and issues an HTTP redirect (now `RedirectController`, split out from the original single controller specifically so it could stay public once admin authentication was added to everything else — see the "RedirectController split" Key Design Decision in [ARCHITECTURE.md](ARCHITECTURE.md)).
- **Get metadata** — `GET /api/v1/urls/{shortCode}`. Returns the stored details (original URL, click count, active flag, timestamps) for a given short code.

Everything below — analytics, reliability, the update endpoint, security, error handling — is this session's AI-assisted work *on top of* that foundation, which is why the rest of this document is brownfield/ambiguous-heavy: most of the actual decomposition-execution-validation loop happened extending and fixing an existing system, not building one from nothing. The one genuine from-scratch greenfield build *within* this session is analytics, below.

### Click Analytics

**Requirement.** The assignment scenario names "analytics" as a required capability alongside core APIs and reliability. At the point this work started, the only analytics signal was a raw `click_count` integer — no way to see trends, time-of-day patterns, or browser/referrer breakdown.

**Task decomposition.** Interpreted "analytics" as needing: (1) a data model that supports aggregation, not just a counter; (2) a way to record events without slowing down the thing being measured (the redirect); (3) a read endpoint to expose it. That ordering — model, then write path, then read path — set the actual build sequence.

**AI-assisted execution.**
- New `click_analytics` table/entity (one row per redirect: `short_url_id`, `clicked_at`, `referrer`, `user_agent`), chosen over extending the counter specifically because an event table can answer "clicks over time," which a single integer can't — see the "Event table instead of only a counter" decision in [ARCHITECTURE.md](ARCHITECTURE.md).
- `ClickAnalyticsRepository` with three aggregate queries (`countByShortUrlId`, `findFirstClickAt`/`findLastClickAt`, a `GROUP BY CAST(clicked_at AS date)` daily breakdown).
- `GET /api/v1/urls/{shortCode}/stats` returning total clicks, first/last click, and the daily breakdown.
- [feature/click-analytics (PR #2)](https://github.com/manjushasoji/url-shortener-app/pull/2)

**Validation.** Unit tests for the repository projection, the service's aggregation logic, and the controller response shape. Explicitly *not* validated: the `CAST(... AS date)` query against a real MySQL instance — flagged as a known gap in README rather than silently assumed to work, since this session has no local database to run it against. This later became relevant: [reliability/health-checks-and-expiration-fix (PR #5)](https://github.com/manjushasoji/url-shortener-app/pull/5) through [reliability/async-click-recording (PR #7)](https://github.com/manjushasoji/url-shortener-app/pull/7) extended this same greenfield surface with health checks, rate limiting, and async recording, each following the same decomposition → execution → validation loop and each documented as its own Key Design Decision.

## Brownfield

### Fixing Undercounted Clicks (301 → 302)

**Codebase reasoning.** After the analytics feature above shipped, the user reported real, hands-on testing: clicking a short link twice from the same browser only incremented the count once; a fresh browser incremented it again. That symptom — works once per browser, not once per click — pointed away from application logic (which had no per-browser state) and toward something the *browser* was doing.

**Task decomposition.** Diagnose before touching code: identify why a browser would stop re-requesting after the first click. The redirect endpoint returned `301 Moved Permanently`, which is a spec-cacheable response — a browser given a 301 once resolves the short link from its own cache on every later click, never re-hitting the server. That's the entire bug: no server-side click after the first one, for that browser.

**AI-assisted execution.** Changed the response from `301` to `302 Found` in [UrlShortenerController.java](../src/main/java/com/urlshortener/controller/UrlShortenerController.java), updated the Swagger annotation and the one test asserting the status code, and documented the trade-off explicitly: the service loses the browser-caching benefit a 301 gave, accepted because click accuracy is the core feature this project is being evaluated on. [fix/redirect-302-for-click-counting (PR #3)](https://github.com/manjushasoji/url-shortener-app/pull/3)

**Validation.** The fix was verified logically (HTTP caching semantics for 301 vs. 302 are well-documented, not something this session could test against a real browser), and the reasoning was written into [ARCHITECTURE.md](ARCHITECTURE.md) as a Key Design Decision so a future reader doesn't "fix" it back to 301 without knowing why.

### Enhancement: Adding Update/Deactivate Capability

**Requirement.** Not in the system at any point before this: the original three endpoints could create and read a link, but never change or disable one. The initial requirements audit against the assignment's deliverables had already flagged this explicitly — "no list, update, delete, or deactivate operations; a link can never be turned off once created" — and it was later requested directly: add an endpoint to update `active` and `expiresAt`, with tests. This is a textbook brownfield task per the assignment's own scope definition ("enhancements, refactors, bug fixes" to an existing system) — extending a working, already-deployed set of endpoints rather than building on a blank slate.

**Codebase reasoning.** `ShortUrl` already had `setActive`/`setExpiresAt` mutators sitting unused (the entity was designed with lifecycle fields from the start, just never wired to an endpoint). The existing `CreateShortUrlRequest`/`GlobalExceptionHandler`/controller `@Operation` annotation patterns were the templates to follow, not invent from scratch — the task was extending three existing conventions consistently, not choosing new ones.

**Task decomposition.** (1) A DTO for the partial update — decide which fields are mutable and how "not provided" behaves; (2) a validation rule for the nonsensical case (an all-empty update); (3) a service method reusing the existing entity's mutators; (4) a controller endpoint matching the existing four's documentation style.

**AI-assisted execution.** New `UpdateShortUrlRequest(Boolean active, LocalDateTime expiresAt)` (reusing the same `@Future` validation already on `CreateShortUrlRequest.expiresAt`), a new `InvalidUpdateRequestException` following the codebase's one-exception-per-failure-mode pattern, `UrlShortenerServiceImpl.updateShortUrl` applying only non-null fields, and `PATCH /api/v1/urls/{shortCode}`. [feature/update-short-url-endpoint (PR #16)](https://github.com/manjushasoji/url-shortener-app/pull/16)

**A design decision this enhancement forced**: what should a `null` field in the request mean — "leave unchanged" or "clear it"? Jackson can't distinguish an omitted field from an explicit `null` on a plain record without extra tooling, so one convention had to be picked. "Unchanged" was chosen (matching typical PATCH semantics), with the resulting limitation stated explicitly in the same PR rather than left implicit: an already-set `expiresAt` cannot be cleared back to permanent through this endpoint. Unlike the wrong-URL ambiguity in the next section, this wasn't put to the user — there's no way to ask that scales past every future ambiguous field, and both options were reasonable defaults rather than materially different features. That's the other valid mode for resolving ambiguity: when the choice has a bounded, statable consequence, document it instead of interrupting.

**Validation.** Nine new tests across service and controller layers: deactivate, update `expiresAt`, update both, leaving an unspecified field unchanged, rejecting an empty update, `404` for a missing code, and a `400` for a past `expiresAt` exercised end-to-end through real Bean Validation via MockMvc — not just asserted at the unit level. Not verified: a live-database round-trip (the standing limitation on every feature in this project, given no local MySQL in this session).

### Course-Corrections: When the First Fix Was Wrong

Adding `@Mock private ClickAnalyticsRecorder` to a test broke CI with `Could not modify all classes [class java.lang.Object, ...]`. The first fix — a Surefire JVM flag (`-Djdk.attach.allowAttachSelf=true`) — was applied and reported back as not working. Rather than guess a second flag, the actual full stack trace was requested, which revealed the real cause: Byte Buddy not yet supporting a newer JDK than it was built against, unrelated to the original self-attach hypothesis. The JVM-flag fix was reverted, `ClickAnalyticsRecorder` was split into an interface (still useful for other reasons, but *not* the fix), and the actual fix (`-Dnet.bytebuddy.experimental=true`) was applied — with the earlier commit's documentation corrected to say the first attempt was wrong, rather than left standing. [fix/click-analytics-recorder-interface (PR #15)](https://github.com/manjushasoji/url-shortener-app/pull/15). The same pattern repeated one task later: a "consistent 404 for unmapped paths" fix ([PR #17](https://github.com/manjushasoji/url-shortener-app/pull/17)) was reported as still returning `500` in production; the actual mechanism (`NoResourceFoundException` bypassing the `/error` handler entirely via Spring 6.1's controller-advice flow) was diagnosed and fixed separately ([PR #18](https://github.com/manjushasoji/url-shortener-app/pull/18)), again with the prior PR's documentation corrected rather than left inaccurate.

## Ambiguous: "Add a 4XX Error for Wrong URL"

**Requirement understanding.** The literal request — "add a 4XX error for wrong url" — had at least three defensible readings in this codebase: (1) the `originalUrl` field on create, which already had validation; (2) a malformed short code in the path, which fell through to a working but perhaps-imprecise 404; (3) a path that doesn't match any route in the API at all, which returned Spring Boot's default whitelabel page. Implementing any one of these without asking risked solving the wrong problem — a real cost here, since each round trip through this session's own toolchain gaps (no local Java/Maven) is expensive to redo.

**Decomposition.** Rather than guess, the three readings were surfaced directly as options (via a structured clarifying question) with what each one would concretely involve and what already existed for it. The user picked "unknown/unmapped path."

**AI-assisted execution.** Two attempts, both real engineering judgment calls: the first ([PR #17](https://github.com/manjushasoji/url-shortener-app/pull/17)) deliberately avoided the more commonly-documented `throw-exception-if-no-handler-found` + `add-mappings=false` combo, because the second property also disables the `/webjars/**` mapping Swagger UI depends on — a risk that couldn't be verified without a local build, so a safer, narrower mechanism (`ErrorController`) was chosen instead, explicitly trading completeness for safety. As the brownfield section above describes, that mechanism turned out not to cover the actual reported case, and was corrected in [PR #18](https://github.com/manjushasoji/url-shortener-app/pull/18).

**Validation.** Both `ApiErrorController` and the `NoResourceFoundException` handler have unit tests; neither is exercised through a real running `DispatcherServlet` in this session (no local server to hit), which is called out explicitly in README's "Not yet covered" rather than left implicit.

## What This Demonstrates

Across all three: no task was implemented and left undocumented. Every trade-off has a named location in README or ARCHITECTURE (searchable by "Known Limitations" or "Key Design Decisions"), every fix that turned out to be wrong was corrected in its own documentation rather than quietly patched over, and every genuinely ambiguous request was either surfaced as an explicit choice or resolved with a stated, bounded default. That traceability — not just the working code — is the actual deliverable this document is pointing at.
