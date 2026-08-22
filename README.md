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

./gradlew bootRun
```

- UI: <http://localhost:8082/orders>
- Camunda webapps (Cockpit / Tasklist / Admin): <http://localhost:8082/camunda> (`demo` / `demo`)

The credentials above are local demo values only.

### Tests

```bash
./gradlew test
```

Tests run against in-memory H2 with the job executor disabled, so timer jobs are
triggered deterministically (`ClockUtil` + `managementService.executeJob`) rather
than racing a background thread.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/orders` | create an order and start a process instance |
| `POST` | `/api/orders/{correlationId}/payment` | correlate a `PaymentReceived` message |
| `GET` | `/api/orders/{correlationId}` | look up a single order |

The Thymeleaf UI at `/orders` drives the exact same `OrderService`, so clicking
around exercises the real engine rather than a parallel code path.

## Stack

Java 21 · Spring Boot 4.1 · Camunda Platform 7.24 · Spring Data JPA · Thymeleaf · PostgreSQL
