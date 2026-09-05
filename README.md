# Camunda 7 Demo — Message Correlation, DMN & Saga

A small Spring Boot application that demonstrates Camunda Platform 7 running as an
**embedded engine** (no standalone Camunda server required) around a single
realistic scenario: an order that waits for a payment, gets risk-assessed, and
compensates itself when a downstream step fails.

## What it demonstrates

| Concept | Where |
|---|---|
| **Message correlation** by a business key (`correlationId`) | `OrderService.notifyPaymentReceived` |
| **Event-based gateway** (race between message and timer) | `Gateway_WaitForEvent` |
| **DMN decision table** driving process routing | `payment-risk-assessment.dmn`, `Task_AssessRisk` |
| **Exclusive gateway** with a default flow | `Gateway_RiskCheck` |
| **User task** with a candidate group | `Task_ManualReview` (`risk-review`) |
| **Boundary error event** + **saga / compensation** | `Boundary_ShippingFailed`, `Task_RevertPayment` |
| **Async continuation** as a transaction boundary | `asyncBefore` on `Task_NotifyShipping` |
| **Job retries and incidents** | `failedJobRetryTimeCycle`, `ShippingRetryAndIncidentTest` |
| **Job executor tuning** | `camunda.bpm.job-execution` in `application.yaml` |
| **Process versioning & instance migration** | `ProcessVersioningAndMigrationTest` |
| **External task pattern** (topic + worker) | `Task_GenerateInvoice`, `InvoiceWorker` |
| **Kafka in** — event correlates a waiting instance | `PaymentEventConsumer` |
| **Kafka out** — transactional outbox | `OrderEventOutbox`, `OutboxPublisher` |
| **OAuth2 resource server** (Keycloak, JWT) | `SecurityConfig`, `KeycloakRealmRoleConverter` |
| **Soft delete** + process instance termination | `OrderService.softDelete`, `OrderSoftDeleteTest` |
| **Java delegates** wired via `delegateExpression` | `az.company.camunda.order.*Delegate` |
| **Process testing** with `camunda-bpm-assert` | `src/test/java/.../order/*Test.java` |

## The process

```
                                       ┌─ HIGH risk ─► Manual Risk Review ─┐
Order Created ─► Create Order ─► Assess Risk ─► Risk? ─┤                    ├─► Wait for Payment or Timeout
                                       └─ otherwise ───────────────────────┘         │
                                                                                     │
                        ┌── Payment Received (message) ──► Mark Order Paid ─► Notify Shipping ─► Generate Invoice ─► Order Completed
                        │                                        ▲                  │              (external task)
                        │                                  (compensate)             │ BpmnError
                        │                                  Revert Payment ◄─────────┘
                        └── Payment Timeout (PT1H) ──────► Cancel Order ─► Order Cancelled
```

`Assess Risk` evaluates a DMN table on the order amount:

| Amount | Risk level | Effect |
|---|---|---|
| `< 100` | `LOW` | straight to payment wait |
| `[100..1000)` | `MEDIUM` | straight to payment wait |
| `>= 1000` | `HIGH` | routed through a manual review user task first |

### Business failure vs. technical failure

`Notify Shipping` is marked `asyncBefore`, so the payment is durably committed
before the external call is attempted — a crash mid-call can never lose the fact
that the customer paid. From there the two failure kinds diverge, which is the
whole point of the step:

| Customer name contains | Delegate throws | Engine response | Order ends up |
|---|---|---|---|
| `ShipFail` | `BpmnError` | boundary error event → compensation | `REFUNDED` |
| `TechFail` | `IllegalStateException` | job retried per `R3/PT10S`, then an incident | stays `PAID`, process parked |

A rejected shipment is a modelled business outcome, so it compensates. A broken
connection is not — retrying is the correct response, and once retries are
exhausted an operator resolves the incident in Cockpit rather than the system
silently refunding a paying customer.

### Service task vs. external task

`Notify Shipping` and `Generate Invoice` sit next to each other on purpose —
they are the same kind of step wired two opposite ways:

| | `Task_NotifyShipping` (delegate) | `Task_GenerateInvoice` (external) |
|---|---|---|
| Who calls whom | engine calls our bean | worker asks the engine for work |
| BPMN refers to | `delegateExpression` → a bean | `camunda:topic` → nothing of ours |
| Runs on | job executor thread, in the engine transaction | the worker's own thread, its own transaction |
| Failure semantics | `BpmnError` / exception thrown from the delegate | `handleBpmnError` / `handleFailure` reported back |
| Who decides retries | the model (`failedJobRetryTimeCycle`) | the worker, per failure |
| Worker down | job fails and retries | nothing happens; the instance just waits |

The external task is a **wait state**: reaching the activity runs none of our
code, it only publishes the work on a topic. That is what decouples the two
lifecycles — a worker can be redeployed, scaled out, or offline for an hour
without a single instance failing.

`fetchAndLock` is the whole concurrency story. Ten identical workers can poll
one topic; the lock is what stops two of them doing the same invoice. The lock
duration carries the same trade-off as the job executor's `lock-time-in-millis`
— shorter than the slowest real call and the work runs twice.

> **On the transport.** A real worker polls `/engine-rest` over HTTP via
> `camunda-external-task-client`, which is what lets it be a separate
> deployment in any language. That is not possible on this stack: Camunda
> 7.24's REST starter is Jersey-based and Spring Boot 4 removed Jersey support,
> so there is no `/engine-rest` to expose. `InvoiceWorker` therefore polls the
> engine-side `ExternalTaskService` directly on a `@Scheduled` loop. The
> operations are the same ones the REST client wraps — `fetchAndLock`,
> `complete`, `handleFailure` — so only the network hop is missing, not the
> pattern.

### Securing the API with Keycloak

`/api/**` is an OAuth2 **resource server**. It validates a bearer token against
Keycloak's published signing keys and authorizes on the roles inside it — no
password ever reaches this application, and there is no auth round trip per
request. `issuer-uri` makes it fetch OIDC metadata once at startup, which also
means the `iss` claim is checked: a correctly signed token from a *different*
realm is rejected rather than trusted.

Roles come from the realm import, so they are part of the repo:

| User | Password | Realm role | May |
|---|---|---|---|
| `alice` | `alice` | `order-admin` | create orders, report payments, read |
| `bob` | `bob` | `order-viewer` | read only |
| `carol` | `carol` | *(none)* | nothing |

#### The one thing that always breaks: role mapping

A Keycloak access token looks like this:

```json
"realm_access": { "roles": ["order-admin"] },
"scope": "email profile"
```

Spring Security's default converter reads `scope`/`scp`. Against that token it
produces `SCOPE_email`, `SCOPE_profile` and **no roles at all**, so every
`hasRole` check fails on a token that is completely valid — which reads like a
token problem and is actually a mapping problem. Hence
`KeycloakRealmRoleConverter`, which digs `realm_access.roles` out and adds the
`ROLE_` prefix that `hasRole("order-admin")` expands to. (Client roles, if you
use them, are somewhere else again: `resource_access.<clientId>.roles`.)

#### 401 vs. 403

The tests keep these apart deliberately: **401 = we do not know who you are,
403 = we do and you may not.** Verified against real Keycloak tokens:

| Caller | `POST /api/orders` |
|---|---|
| no token | `401` |
| malformed token | `401` |
| `carol` (no roles) | `403` |
| `bob` (viewer) | `403` |
| `alice` (admin) | `200` |

#### Three filter chains, not one

The API, the Camunda webapps and the Thymeleaf UI have genuinely different
needs, and one chain would mean weakening all three to the weakest:

- **`/api/**`** — stateless bearer tokens, so no session, no cookie, and
  therefore CSRF protection off. That is the only place it is off.
- **`/camunda/**`** — Camunda ships its own login *and* its own CSRF filter;
  layering Spring Security's on top rejects its POSTs.
- **everything else** — open so the demo stays clickable, but CSRF stays **on**.
  Thymeleaf injects the token into every `th:action` form.

> **Scope note.** This secures the REST API. Single sign-on *into Cockpit /
> Tasklist* is a different job — it needs `ContainerBasedAuthenticationFilter`
> plus an `AuthenticationProvider` that maps the OIDC principal into Camunda's
> identity service, or the community Keycloak identity plugin. That plugin
> targets Spring Boot 3, and this project is on Boot 4, which has already cost
> this repo Jersey (`/engine-rest`) and the Kafka auto-configuration package
> move. It is left out rather than half-wired.

### Kafka: the dual write, and how the outbox removes it

The obvious way to publish an `ORDER_PAID` event is to call the producer from
inside `MarkOrderPaidDelegate`. That is a **dual write**: the engine commits
order state and process state to Postgres, the producer sends to a broker, and
nothing ties the two together. Crash in the gap and you get one of two bad
outcomes — an event for a step that rolled back, or a committed step the world
never hears about. There is no ordering of the two calls that fixes it.

So the delegates don't touch Kafka. They write an `outbox_event` row **in the
same transaction** as the status change:

```java
order.markPaid();
orderRepository.save(order);
outbox.record(ORDER_PAID, correlationId, order.getCustomerName(), order.getAmount());
```

Same database, same transaction, so all three commit or none do. `OutboxPublisher`
then drains unpublished rows to Kafka on a schedule, after the commit.

That leaves exactly one honest weakness, and it is worth naming rather than
hiding: the broker acknowledgement and the `publishedAt` update are still two
systems. A crash between them republishes the row, so delivery is
**at-least-once**. The alternative — marking the row published first — loses
events instead, which is strictly worse. Hence:

- the Kafka **key is the correlationId**, so one order's events share a
  partition and stay in order, and consumers can deduplicate on it;
- the publisher **stops at the first failure** instead of skipping past it,
  because publishing a later event before an earlier one defeats the keying;
- consumers must be idempotent — which is exactly what the inbound side does.

### Kafka inbound: what makes correlation from a topic hard

`PaymentEventConsumer` makes the same `correlateWithResult()` call the REST
endpoint makes. The transport changed; the process didn't. What changes is the
delivery guarantees around it:

| Situation | Response | Why |
|---|---|---|
| Event redelivered after the order was paid | no-op | order status is the dedup key; failing here would retry forever |
| Order is in **manual review**, not at the message event | throw → backoff → retry | it's alive and will accept the payment, just not yet |
| No such order | throw, marked **non-retryable** → DLT | waiting cannot conjure up an order |

The middle row is the one worth remembering: a correlation failure usually means
*too early*, not *invalid*. A HIGH-risk order parked at `Task_ManualReview` is
exactly that case, and `PaymentEventConsumerTest` drives it end to end — the
event fails, the reviewer completes the task, the same event then correlates.

Without a dead letter topic a permanently failing record is retried forever and
**blocks its partition**, stopping every well-formed event behind it.

### Versioning and migration

Deploying a changed model is additive: Camunda stores it as a new *version* of
the same process definition key and leaves every already-running instance pinned
to the definition it was started on. That is what makes deployment safe — it can
never corrupt work in flight — but it also means old instances keep executing the
old model indefinitely, so after a deployment you can have two instances that
behave differently on the same click.

Moving them across is an explicit operation:

```java
MigrationPlan plan = runtimeService.createMigrationPlan(oldDefinitionId, newDefinitionId)
        .mapEqualActivities()
        .build();

runtimeService.newMigration(plan).processInstanceIds(ids).execute();
```

`mapEqualActivities()` maps only the activity ids present in both models. If the
new version renamed the activity an instance is currently sitting on, the plan
still builds but execution is rejected with
`MigratingProcessInstanceValidationException` and the instance is left untouched
— the engine will not guess where that token belongs. The fix is an explicit
`.mapActivities("Task_ShipOrder", "Task_DispatchOrder")`, not a retry.

## Running it

Postgres is the only external dependency (the Camunda engine itself runs inside
the Spring Boot process):

```bash
docker run -d --name camunda-postgres -p 5432:5432 \
  -e POSTGRES_DB=camunda -e POSTGRES_USER=camunda -e POSTGRES_PASSWORD=camunda \
  postgres:15

# Kafka (single-node KRaft, no Zookeeper), a Kafka UI, and Keycloak - whose
# realm, roles and users are imported from keycloak/realm-camunda-demo.json so
# nothing has to be clicked together. Postgres is deliberately not in this
# compose file; plenty of people already have one on 5432.
docker compose up -d

./gradlew bootRun
```

Calling the secured API:

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8083/realms/camunda-demo/protocol/openid-connect/token \
  -d grant_type=password -d client_id=camunda-demo-api \
  -d client_secret=demo-client-secret \
  -d username=alice -d password=alice | jq -r .access_token)

curl -X POST http://localhost:8082/api/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerName":"Alice","amount":120}'
```

Publishing a payment event instead of clicking *Simulate Payment* — the key is
the correlationId:

```bash
docker exec -i camunda-demo-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic payment-events \
  --property parse.key=true --property key.separator=:
> <correlationId>:<correlationId>
```

Watching what the process emits:

```bash
docker exec -i camunda-demo-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic order-events --from-beginning
```

…or just open the Kafka UI, which shows the same thing with the message bodies
expanded. Worth looking at `payment-events-dlt` after sending an event for a
correlationId that does not exist: the dead-lettered record carries its own
reason in the headers (`kafka_dlt-exception-cause-fqcn`,
`kafka_dlt-exception-message`).

- UI: <http://localhost:8082/orders>
- Camunda webapps (Cockpit / Tasklist / Admin): <http://localhost:8082/camunda> (`demo` / `demo`)
- Kafka UI (topics, partitions, message bodies): <http://localhost:8084>
- Keycloak admin console: <http://localhost:8083> (`admin` / `admin`)

Every credential in this repository is a local demo value.

### Why the broker advertises two addresses

"Where do I reach the broker" has two different answers here, and a client is
told which address to come back on in the metadata response — so the address has
to be right *from that client's point of view*. The app runs on the host and
needs `localhost:9092`; the Kafka UI container needs the compose service name,
`kafka:29092`. One listener cannot be both, hence:

```yaml
KAFKA_LISTENERS: PLAINTEXT://:9092,INTERNAL://:29092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,INTERNAL://kafka:29092
KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

This is the usual cause of "the broker is up but my container cannot connect to
it" — the connection succeeds, the metadata response then points the client at
an address it cannot resolve, and it fails on the *next* call.

### Checking Keycloak

The admin console is one way, but the useful check is what the tokens actually
carry — it is the same data the API authorizes on:

```bash
for u in alice bob carol; do
  curl -s -X POST http://localhost:8083/realms/camunda-demo/protocol/openid-connect/token \
    -d grant_type=password -d client_id=camunda-demo-api \
    -d client_secret=demo-client-secret -d username=$u -d password=$u \
  | jq -r .access_token | cut -d. -f2 | base64 -d 2>/dev/null \
  | jq -c '{user: .preferred_username, roles: .realm_access.roles, scope}'
done
```

```
{"user":"alice","roles":["order-admin"], "scope":"email profile"}
{"user":"bob",  "roles":["order-viewer"],"scope":"email profile"}
{"user":"carol","roles":null,            "scope":"email profile"}
```

Note that `scope` is identical for all three. Everything that distinguishes them
is in `realm_access.roles` — which is precisely why
[the custom converter](#the-one-thing-that-always-breaks-role-mapping) exists.

### Tests

```bash
./gradlew test
```

Tests run against in-memory H2 with the job executor disabled, so timer jobs are
triggered deterministically (`ClockUtil` + `managementService.executeJob`) rather
than racing a background thread.

## API

All of these require a bearer token — see [Securing the API with Keycloak](#securing-the-api-with-keycloak).

| Method | Path | Required role | Purpose |
|---|---|---|---|
| `POST` | `/api/orders` | `order-admin` | create an order and start a process instance |
| `POST` | `/api/orders/{correlationId}/payment` | `order-admin` | correlate a `PaymentReceived` message |
| `GET` | `/api/orders/{correlationId}` | `order-viewer` or `order-admin` | look up a single order |
| `DELETE` | `/api/orders/{correlationId}` | `order-admin` | soft-delete an order and terminate its process instance |

The Thymeleaf UI at `/orders` drives the exact same `OrderService`, so clicking
around exercises the real engine rather than a parallel code path.

The generated OpenAPI contract is served at `/v3/api-docs`, with Swagger UI at
`/swagger-ui.html`.

### Errors

Every failed call returns one body shape, produced by the single
`@RestControllerAdvice` in `exception/`:

```json
{
  "status": 404,
  "errorCode": "ORDER_NOT_FOUND",
  "message": "No order was found for correlation ID 6f1c-....",
  "timestamp": "2026-09-04T06:12:44.318Z",
  "path": "/api/orders/6f1c-...",
  "traceId": "0f2b8c4a-1d3e-4f5a-9b7c-2e8d6a1f0c33"
}
```

`errorCode` is the stable field to branch on; `message` is localized and will
change with the caller's language. The same `traceId` comes back in the
`X-Trace-Id` response header and is what the server logged the failure under —
quote it in a bug report and the log line is one grep away. Nothing else about
the failure reaches the client: no stack trace, no exception class, no SQL.

### Soft delete

`DELETE` marks the row with a `deletedAt` timestamp instead of removing it, so
the order's business history survives, and returns `404` on every read
afterwards — including a second delete. The status enum is left alone: whether
an order was `PAID` or `CANCELLED` when it was deleted is worth keeping.

The process instance is *not* left alone. A deleted order that still has an
instance parked at `Gateway_WaitForEvent` would silently come back to life as
`PAID` the moment a payment message arrived, so the delete terminates every
instance carrying that business key — a `list()` rather than a `singleResult()`,
because Camunda does not enforce business key uniqueness.

## Localization

Three languages: **Azerbaijani** (`az`, the default), **English** (`en`) and
**Spanish** (`es`).

| Surface | How the language is chosen |
|---|---|
| Thymeleaf UI at `/orders` | `?lang=az\|en\|es`, stored in a cookie so it survives the next click |
| REST API under `/api/orders` | the `Accept-Language` request header |
| Neither present | Azerbaijani |

An unsupported language falls back to Azerbaijani rather than half-translating
the page, and `?lang=fr` clears the cookie instead of parking a language the
application has no bundle for.

Both surfaces share one `LocaleResolver` (`config/I18nConfig`), because Spring
allows only one per DispatcherServlet: a cookie resolver whose default is the
request's `Accept-Language`. A plain cookie resolver would have made the API
ignore the header; a plain header resolver would have made the UI forget the
switcher.

### Adding or changing a string

Bundles live in `src/main/resources`:

| File | Language |
|---|---|
| `messages.properties` | Azerbaijani — the default bundle **and** the fallback |
| `messages_en.properties` | English |
| `messages_es.properties` | Spanish |

There is deliberately **no `messages_az.properties`**. Azerbaijani is the
default bundle, and a second copy of those strings would only be somewhere for
them to drift.

A key added to one bundle must be added to all three: `MessageBundleParityTest`
compares the key sets and fails the build on a missing translation, because a
forgotten key does not fail at runtime — it silently renders Azerbaijani inside
an otherwise Spanish page.

Two things to know when editing a bundle:

- A single quote is `MessageFormat`'s escape character. In any message that
  takes `{0}` parameters, an apostrophe has to be doubled.
- `order.format.dateTime` is a date *pattern*, not a sentence — the day/month
  order is part of what changes between locales.

## Stack

Java 21 · Spring Boot 4.1 · Camunda Platform 7.24 · Spring Data JPA · Thymeleaf ·
PostgreSQL · Apache Kafka · Keycloak · Spring Security (OAuth2 resource server) ·
springdoc-openapi
