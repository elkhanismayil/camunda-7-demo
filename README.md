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
| **Java delegates** wired via `delegateExpression` | `az.company.camunda.order.*Delegate` |
| **Process testing** with `camunda-bpm-assert` | `src/test/java/.../order/*Test.java` |

## The process

```
                                       ┌─ HIGH risk ─► Manual Risk Review ─┐
Order Created ─► Create Order ─► Assess Risk ─► Risk? ─┤                    ├─► Wait for Payment or Timeout
                                       └─ otherwise ───────────────────────┘         │
                                                                                     │
                        ┌── Payment Received (message) ──► Mark Order Paid ─► Notify Shipping ─► Order Completed
                        │                                        ▲                  │
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

An order whose customer name contains `ShipFail` makes `Notify Shipping` throw a
`BpmnError`, which triggers compensation and flips the already-`PAID` order to
`REFUNDED` — the saga pattern, end to end.

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
