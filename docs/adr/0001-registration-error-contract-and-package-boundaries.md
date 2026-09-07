# 1. Registration: error contract and package boundaries

Date: 2026-09-05
Status: accepted
Context: `feature/user-registration-login`, Part 2 of
`docs/superpowers/specs/2026-09-05-keycloak-auth-design.md`

The design spec settled *what* self-service registration does (one code path
for both surfaces, `order-viewer` granted by our code, no token endpoint, no
`keycloak-admin-client`). It left five things open that had to be decided while
implementing. This records them so a reviewer does not re-derive them, and so
the next person to add an endpoint knows which shape to follow.

## 1. Exception categories live in `exception`, concrete failures in `auth`

`.claude/rules/error-handling.md` wants one `@RestControllerAdvice` in
`exception/`, and `code-design.md` forbids package cycles. Putting
`DuplicateUsernameException` next to the advice would drag registration
vocabulary into the most-depended-on package; putting the advice next to the
exception would make `exception → auth → exception`.

**Decision.** `exception` holds the abstract categories only —
`ApplicationException` plus `ConflictException`, `InvalidRequestException`,
`UpstreamUnavailableException`, `UpstreamTimeoutException`. Feature packages
subclass them: `auth.DuplicateUsernameException extends ConflictException`. The
advice handles the four categories and never imports a feature package.

The arrow is `auth → exception`, one way, and a second feature gets its own
statuses for free.

## 2. Upstream failures are `502` / `504`, not `503`

Spec §6 says `503` for "Keycloak unreachable". `error-handling.md` says an
upstream `5xx` or timeout maps to `502` / `504`, and it is the normative
source. `503` also says something untrue: this service is available, its
dependency is not.

**Decision.** `502` when Keycloak refuses, fails or cannot be reached, `504`
when it does not answer in time. The split is worth keeping because the two
need different operational responses — a refusal is usually configuration, a
timeout is usually load. Both are asserted in `RegistrationApiTest`.

## 3. The REST advice is scoped to `@RestController`

This repository had no advice at all, and it serves Thymeleaf pages from the
same context. An unscoped `@RestControllerAdvice` would answer a browser with
a JSON body the moment anything failed on `/orders`.

**Decision.** `@RestControllerAdvice(annotations = RestController.class)`. The
sign-up form re-renders itself instead, through an `@ExceptionHandler` on
`RegistrationViewController` — the browser gets the page back, the API gets the
one JSON shape. The generic `Exception` handler rethrows `AccessDeniedException`
and `AuthenticationException`, or every 403 would become a 500.

## 4. A failed role assignment is reported, not compensated

Creating the user and granting `order-viewer` are two Admin API calls with no
transaction around them. If the grant fails, the account exists with no role.

**Decision.** Surface the failure; do not delete the user. Deleting is a fourth
admin call with its own failure mode, and this service would then be reporting
an outcome it cannot guarantee either. An error a person can act on beats a
"success" that logs in to a 403 on every page. The user is left in Keycloak and
the failure is logged with the username and the `traceId`.

## 5. `traceId` is read from MDC, or generated

`error-handling.md` requires a `traceId` on every error response, sourced from
MDC per `configuration.md`. This service has no log4j2 and no MDC-populating
filter — that migration is its own ticket and does not belong on this branch.

**Decision.** `GlobalExceptionHandler` reads `traceId` from MDC and falls back
to a generated UUID, logging it either way. When a filter is added later it is
picked up with no change here. Until then the id correlates the response with
its log line, which is the property that matters.

## Consequences

- A new feature package throwing `ConflictException` gets a `409` in the right
  shape with no change to the advice.
- The two surfaces answer differently by construction, and a test asserts each.
- `503` never appears; monitoring can treat `502`/`504` as dependency signals.
- Until an MDC filter exists, a `traceId` is per-response rather than
  per-request — it will not tie together two calls in one trace.
