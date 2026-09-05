# Keycloak authentication and self-service registration

Status: accepted, in implementation
Date: 2026-09-05
Branch: `feature/user-registration-login`

This is the source of truth for the whole feature. It is written in two parts.
Part 1 (browser login, authorization, realm and build changes) is implemented
on the branch above. Part 2 (self-service registration) is executed separately,
by an agent whose only context is this file.

---

## 1. Problem

The service has two HTTP surfaces and today only one of them is secured.

- `/api/orders/**` is an OAuth2 resource server. It validates a bearer token
  against Keycloak's published keys and authorizes on realm roles. This works.
- The Thymeleaf UI under `/orders` is `permitAll`. Anyone reaching the port can
  create, pay for, and delete orders. The two surfaces drive the *same*
  `OrderService`, so the API's authorization rules are decoration as long as the
  UI can be used to bypass them.

There is also no way to obtain an account. `alice`, `bob` and `carol` are
imported from a realm file. A demo of authentication that cannot demonstrate
sign-up is missing half the story.

## 2. Settled decisions

These came out of a brainstorming session with the user and are **not open**.
An agent implementing Part 2 must not re-derive or re-litigate them.

### D1 — Both surfaces get authentication, with different mechanisms

The browser gets an **OIDC session** (`oauth2Login`, authorization-code flow,
cookie-backed session). The REST API keeps its **bearer token** resource server,
unchanged.

*Rationale.* They are different clients with different threat models. A browser
cannot hold a client secret and needs a session cookie, which brings CSRF into
scope. A machine client has no cookie for an attacker to ride and no place to
run a redirect. Collapsing them into one chain means weakening both to the
weaker. This is why `SecurityConfig` already carries separate filter chains,
and the split is preserved.

### D2 — Registration goes through our own endpoint, not Keycloak's

`POST /api/v1/registrations` on this service. Keycloak's built-in self-service
registration page is **not** enabled.

*Rationale.* New users must land with a role and a known profile shape. Keycloak's
registration page creates a user with no realm role, which in this application is
a user who can authenticate and then see a 403 on every page — the worst outcome
of the three. Owning the endpoint means role assignment and account creation are
one operation with one outcome. It also keeps the sign-up contract in this
repository, versioned with the code that depends on it.

### D3 — New users get realm role `order-viewer`, assigned by our code

Immediately after the user is created, through the Keycloak Admin REST API.

*Rationale.* Least privilege: read-only is the correct default for an account
nobody has vetted. Assigning it in our code rather than as a Keycloak realm
default role keeps the decision reviewable in the diff, and keeps it in one
place rather than split between a JSON file and a service.

### D4 — Browser login is a redirect to Keycloak's own login page

`oauth2Login()`. We do **not** build a login form, and the user's password never
reaches this application.

*Rationale.* This is the whole point of delegating to an identity provider. A
local login form means handling a credential we have no business seeing, and it
forfeits everything Keycloak already implements — brute-force detection, password
policy, MFA, account lockout. Registration is the deliberate exception (D2), and
even there the password is forwarded to Keycloak and never stored.

### D5 — No token endpoint on this service

API clients obtain tokens **directly from Keycloak**'s token endpoint. We
document how; we do not proxy it.

*Rationale.* A `/api/v1/token` endpoint on this service would be a credential
collector and a second, weaker copy of a flow Keycloak already exposes correctly.
It would also make this service a hop in every client's auth path, so an outage
here would break authentication for clients that do not otherwise depend on us.
The `camunda-demo-api` client already has `directAccessGrantsEnabled: true`;
that is the documented path.

## 3. Components

### Part 1 — implemented on this branch

| Component | Location | Role |
|---|---|---|
| `SecurityConfig` (UI chain) | `security/SecurityConfig.java` | `@Order(3)` chain gains real authorization rules, `oauth2Login`, RP-initiated logout. CSRF stays on. |
| `KeycloakOidcRealmRoleMapper` | `security/` | `GrantedAuthoritiesMapper`. Reads `realm_access.roles` from the **ID token** and maps to `ROLE_*`. The login-side twin of `KeycloakRealmRoleConverter`. |
| `KeycloakRealmRoles` | `security/` | Package-private holder of the single claim-to-authority mapping, shared by the JWT converter and the OIDC mapper. |
| Realm additions | `keycloak/realm-camunda-demo.json` | Registrar client, ID-token role mapper, post-logout redirect URI. |
| UI header | `templates/orders/index.html` | Username, logout form, `sec:authorize` around write controls. |

### Part 2 — not yet implemented

| Component | Location | Role |
|---|---|---|
| `RegistrationController` | `registration/` | `POST /api/v1/registrations`, `201 Created`. `GET /register` + `POST /register` for the browser form. |
| `RegistrationService` | `registration/` | Orchestrates: create user, then assign `order-viewer`. |
| `KeycloakAdminClient` | `registration/` | `RestClient` over the Keycloak Admin REST API, authenticated with the registrar service account. |
| `RegistrationRequest` / `RegistrationResponse` | `registration/` | Bean-validated request record; response carries username and id, never the password. |
| Exception advice | `registration/` or `security/` | Maps registration failures onto the contract in §6. |
| `register.html` | `templates/` | Sign-up form. CSRF token via `th:action`. |

Part 2 lives in a new **feature package** `az.company.camunda.registration`
(see §8). It may depend on `security` for nothing at all — the intended import
graph is `registration → (spring only)`. It must not import `order`.

## 4. Realm changes

All three are in `keycloak/realm-camunda-demo.json`. Existing roles and users are
untouched.

1. **`camunda-demo-registrar`** — a second confidential client, service accounts
   on, standard flow and direct access grants **off**. It is not a login client;
   it exists only so this service can authenticate as itself to the Admin API.
   Its service account is granted exactly two `realm-management` client roles:
   `manage-users` and `view-realm`. The `realm-admin` composite is deliberately
   **not** granted — it includes realm deletion and client management, neither of
   which a sign-up form needs.

2. **Realm-roles protocol mapper on `camunda-demo-api`, with
   `id.token.claim: true`.** Keycloak's built-in realm-roles mapper writes
   `realm_access.roles` into the *access* token only; `id.token.claim` defaults to
   `false`. `oauth2Login()` derives its authorities from the **ID token**. Without
   this mapper every `hasRole(...)` on the UI chain is false for a user with a
   perfectly valid session — the same failure mode, on the other surface, that
   `KeycloakRealmRoleConverter` exists to prevent. This is the single easiest
   thing in the feature to miss, and it fails silently as a 403.

3. **`post.logout.redirect.uris` = `http://localhost:8082/*`** on
   `camunda-demo-api`. Keycloak 18+ validates the `post_logout_redirect_uri`
   parameter against this attribute; without it, RP-initiated logout ends on a
   Keycloak error page instead of back on the app.

## 5. Authorization rules

Realm roles: `order-admin` (read + write), `order-viewer` (read only).

**API chain, `@Order(1)`, `/api/**`** — unchanged except one addition:
`POST /api/v1/registrations` is `permitAll`, because a person without an account
cannot present a token to get one. Everything else still requires a valid bearer
token.

**Camunda chain, `@Order(2)`** — unchanged. Out of scope; see the README.

**UI chain, `@Order(3)`, catch-all**

| Path | Rule |
|---|---|
| `/`, `/register`, `/error` | permitAll |
| `GET /orders/**` | `hasAnyRole("order-admin", "order-viewer")` |
| `POST /orders`, `POST /orders/*/payment`, `POST /orders/*/delete` | `hasRole("order-admin")` |
| anything else | `authenticated()` |

CSRF stays **enabled** on this chain. It is a cookie-backed session surface;
that is precisely the case CSRF protection is for.

## 6. Error-handling contract

Registration is the only new failure surface. The response body shape follows
Spring's `ProblemDetail` (RFC 7807), which the resource server already emits.

| Condition | Status | Notes |
|---|---|---|
| Payload fails bean validation | `400` | Field-level detail. |
| Username or email already taken | `409` | Keycloak's Admin API returns `409`; pass the *fact* through, not its message body. |
| Password rejected by realm policy | `400` | Keycloak returns `400` with an error description. |
| Keycloak unreachable / 5xx | `503` | Ours is a dependency failure, not a client error. |
| Success | `201` | `Location: /api/v1/registrations/{id}`; body carries username and id. |

Two rules that are part of the contract, not decoration:

- **Never echo a Keycloak error body to the caller.** It leaks realm internals
  and admin endpoint paths.
- **Never log the submitted password**, including inside a request-dump on error.

## 7. Testing strategy

Part 1 is tested without a running Keycloak. This is deliberate and is the same
technique `src/test/resources/application-test.yaml` already documents for the
resource server: `issuer-uri` performs OIDC discovery at **startup**, so a single
`issuer-uri` in the test context makes every `@SpringBootTest` in the repository
require a live container.

The test profile therefore points the `keycloak` **registration** at a
`keycloak-test` **provider** that declares its endpoint URIs explicitly and has
no `issuer-uri`. Nothing is called; the registration id stays `keycloak`, so the
authorization endpoint under test is byte-for-byte the production one.

| Level | Covers |
|---|---|
| `KeycloakOidcRealmRoleMapperTest` | `realm_access.roles` in the ID token becomes `ROLE_*`; absent claim yields no authorities. |
| `OrderUiSecurityTest` | Anonymous `GET /orders` redirects into the OAuth2 login entry point, and that entry point redirects on to Keycloak's authorization endpoint. `order-viewer` reads but cannot write. `order-admin` can write. `/` and `/error` stay open. CSRF is enforced. |
| `OrderApiSecurityTest` | Existing. One assertion changes — see §9. |

Part 2 adds:

- Unit tests for `RegistrationService` with the admin client stubbed: role assignment
  happens, and a failure to assign a role does not report success.
- `@SpringBootTest` slice for `POST /api/v1/registrations` covering each row of §6.
- Optionally a Testcontainers Keycloak integration test (`/x-integration-test`).
  If added, it must reuse a **static shared container**; it is the only test
  permitted to perform issuer discovery.

## 8. Accepted deviations from `.claude/rules/`

Each of these is a knowing exception, recorded so a reviewer does not have to
re-discover it.

| Deviation | Rule | Why accepted |
|---|---|---|
| Branch `feature/user-registration-login` carries no TASK-ID; commits likewise | `branches.md`, `commits.md` | Explicit user instruction. This repository is a public demo with no JIRA project behind it. |
| Feature packages (`order`, `events`, `security`, `registration`) instead of `controller`/`service`/`repository` | `project-package.md` | Pre-existing, repository-wide convention. Restructuring it is out of scope and would violate `engineering-principles.md` §3 (Surgical Changes). New code follows the convention that is here. |
| `/api/orders/**` is unversioned | `api-versioning.md` | Pre-existing. **Not** propagated: the new endpoint is `/api/v1/registrations`. Versioning the existing path is a breaking change for the documented Postman/curl flows and belongs to `backend-architect`, not to this branch. |
| Existing JavaDoc in `SecurityConfig`, `KeycloakRealmRoleConverter`, `OrderApiSecurityTest` is left in place | `documentation.md` | That rule bans JavaDoc on new production code **and** explicitly forbids stripping existing blocks from files touched for other reasons. New code here carries none. |
| Existing test method names do not follow `_when_then` | `testing.md` | Pre-existing. New tests follow the convention; existing names are not mass-renamed. The one existing test whose *behaviour* this change alters is renamed, because it is being rewritten anyway. |

## 9. Rollback and blast radius

There is no database migration and no schema change, so rollback is a revert of
the branch. Two things do **not** roll back with the code and are worth stating:

- The realm file is imported only into a **fresh** Keycloak container.
  `start-dev --import-realm` will not re-import over an existing realm. After
  pulling this change, run `docker compose down` for the Keycloak service and
  bring it back up, or the new client and the ID-token mapper will not exist and
  login will succeed with zero authorities.
- Users created through Part 2 live in Keycloak, not here. Reverting the code
  does not remove them.

## 10. Operating notes

- Log in as `alice` / `alice` for `order-admin`, `bob` / `bob` for `order-viewer`,
  `carol` / `carol` for an authenticated user with no roles (expect 403 — that is
  the correct outcome, not a bug).
- An API token, per D5, comes straight from Keycloak:

      curl -s -X POST http://localhost:8083/realms/camunda-demo/protocol/openid-connect/token \
        -d grant_type=password -d client_id=camunda-demo-api \
        -d client_secret=demo-client-secret -d username=alice -d password=alice

- The client secrets in the realm file and in `application.yaml` are demo values
  for a container that only listens on localhost. They are not credentials for
  anything real, which is the only reason they are allowed to be in the
  repository at all (`dependencies.md` bans committed credentials).
