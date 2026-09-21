# Reliable refund service

Backend exercise with two stages. This README is stage 1. The interviewer adds
stage 2 during the session.

## Context

Our business sells goods and refunds completed payments through a payment
provider. A customer can ask us to refund a payment. The customer never supplies
an idempotency key, so the service supplies one.

- One payment receives one full refund for one customer, so the identity of a
  refund operation is the customer and payment pair.
- The service runs as several instances behind a load balancer and uses one
  PostgreSQL database.
- A customer may repeat an HTTP request after losing its response.
- The provider is idempotent: a repeated call with the same operation ID and the
  same request data returns the original provider refund ID and creates no
  second refund. The service uses its own operation ID as that key.
- Refunds are processed asynchronously. Accepting a request must not wait for the
  provider.

You may use AI and documentation. You own the resulting code, tests, and design
explanation. Keep the editor, terminal, and the AI conversation available for
review.

## Run

```sh
docker compose up -d --wait postgres
./mvnw -B test
./mvnw -B spring-boot:run
```

The application listens on `http://localhost:8080`.

The build targets Java 17. Use JDK 17 or later to build and run, and do not use
APIs newer than Java 17: the compiler rejects them even on a newer JDK.

Database defaults:

```text
JDBC URL: jdbc:postgresql://localhost:5432/commands
Username: commands
Password: commands
```

Override them with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD`. If port 5432 is already taken, change the published
port and the URL together:

```sh
POSTGRES_PORT=5434 docker compose up -d --wait postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/commands ./mvnw -B spring-boot:run
```

Reset the database to a clean state with `docker compose down -v`.

Tests use Testcontainers and their own PostgreSQL container, so they do not touch
the compose database.

## HTTP contract

### POST /v1/refunds

```text
X-Customer-Id: customer-a
Content-Type: application/json

{"paymentId":"payment-123","amountMinor":2500,"currency":"USD"}
```

| Situation | Status |
| --- | --- |
| New operation | 202 |
| Repeat of the same customer, payment, amount, and currency | 200 |
| Same customer and payment with a different amount or currency | 409 |
| Invalid request: missing customer header, blank payment ID, non-positive amount, missing or lowercase currency | 400 |

### GET /v1/refunds/{operationId}

| Situation | Status |
| --- | --- |
| The operation belongs to the requesting customer | 200 |
| The operation is unknown, or belongs to another customer | 404 |

Response body for a found operation:

```json
{"operationId":"6f6f0e3c-4c1a-4d3a-9c5e-2c1a5b6d7e8f","status":"PENDING"}
```

`status` is `PENDING`, `RUNNING`, or `SUCCEEDED`. `stripeRefundId` is present once
the provider has confirmed the refund.

Error bodies are up to you, with one rule: a response for another customer's
operation must not contain that operation's ID, refund ID, or request data.
HTTP 403 would confirm that the operation exists, so use 404.

### Cross-cutting rules

- `X-Customer-Id` represents the authenticated customer and is untrusted input at
  the HTTP boundary.
- The operation ID is an opaque UUID string. Clients must not parse or construct
  it.
- Currency is a three-letter uppercase code. `usd` is invalid.
- The identity is the customer and payment pair. The same payment under a
  different customer is a different refund operation.

## Stage 1 requirements

- When a refund request arrives, the Refund API shall accept it only if the
  customer ID is not blank, the payment ID is not blank, the amount is a positive
  number of minor units, and the currency is a three-letter uppercase code.
- When a request carries a valid customer and payment pair that has no refund
  operation, the Refund API shall create one durable refund operation and shall
  return status `PENDING` with a new operation ID.
- When a request repeats an existing customer, payment, amount, and currency, the
  Refund API shall return the existing operation and shall not create a second
  operation.
- If a request repeats an existing customer and payment pair with a different
  amount or currency, then the Refund API shall return HTTP 409 and shall leave
  the existing operation unchanged.
- While a refund operation is pending, the Refund API shall not call the payment
  provider.
- When a refund operation exists, the Refund API shall return it to its owner
  through `GET /v1/refunds/{operationId}`.
- If the requesting customer is not the owner of the operation, then the Refund
  API shall return HTTP 404 and shall not expose the operation ID, the provider
  refund ID, or the request data.
- When the application restarts, the Refund API shall return every committed
  refund operation.
- While several service instances share one PostgreSQL database, the outcome of a
  repeated request shall not depend on the instance that answers it.
- The service shall return the same operation ID for a repeated request, and
  shall not depend on JVM-local state for correctness.
- The service shall keep the `Stripe` interface and its method signature
  unchanged.

## Stage 1 acceptance cases

1. A new request creates one durable operation and `GET` returns the same
   operation ID with status `PENDING`.
2. Repeating the request returns HTTP 200 with the same operation ID and leaves
   one stored operation.
3. Repeating the customer and payment pair with a different amount returns HTTP
   409 and leaves the stored amount unchanged.
4. Repeating the customer and payment pair with a different currency returns HTTP
   409 and leaves the stored currency unchanged.
5. A customer that does not own an operation receives HTTP 404 without operation
   or refund data.
6. The same payment under a different customer creates a separate operation.
7. Customer and payment IDs containing punctuation do not collide.
8. Concurrent duplicate requests for the same customer and payment produce one
   operation ID and one stored operation. Concurrent requests through one
   application instance are enough to expose a check-then-insert race.
9. An accepted operation is durable: reading it from a new connection after the
   request returns the stored operation.
10. Accepting a new operation leaves the provider call count at zero.
11. A missing customer header, a blank payment ID, a zero or negative amount, a
    missing currency, or a lowercase currency returns HTTP 400.

Write these as automated tests. Use real PostgreSQL for the database behavior.
Coordinate with deterministic gates and bounded waits, not with sleeps.

The design argument is what is being assessed; the tests support it. Explain, in
the pull request, why duplicate requests served by different application
instances still produce one operation, which part of your implementation would
break if those instances did not share JVM state, and why the database rather
than the application decides duplicate creation.

## Test helpers

`src/test/java/interview/executor` contains fixtures you may use:

- `PostgresTestBase` starts one PostgreSQL container for the test JVM and points
  Spring at it. Extend it from a `@SpringBootTest` for database tests.
- `FakeStripe` is a shared provider fake that models provider idempotency. It
  counts attempts and refund effects, and can fail or block the next call.
- `StarterSmokeTest` proves the application starts and Flyway applies the schema.

A test that replaces the demo provider with the fake:

```java
@SpringBootTest
class RefundTest extends PostgresTestBase {

    static final FakeStripe STRIPE = new FakeStripe();

    @TestConfiguration
    static class Provider {
        @Bean
        Stripe stripe() {
            return STRIPE;
        }
    }

    // @Autowired RefundService, JdbcTemplate, RestClient, ...
}
```

## The provider seam

`Stripe`, `StripeException`, and `RefundRequest` form the provider seam. Do not
change the `Stripe` interface: the interview checks depend on it.

When the application does not define its own `Stripe` bean, the starter registers
a demo provider. You can see how often the demo provider was called:

```text
GET /actuator/demo-stripe  ->  {"available":true,"attempts":0,"refunds":0}
```

Stage 1 must leave `attempts` at zero.

## Stage 2

The interviewer will add asynchronous provider processing, durable work,
retries, and crash recovery at the midpoint. Do not implement any of it before
that requirement arrives. Do not add a worker, a work queue, or a provider call
in stage 1.

## Submitting your work

Fork `https://github.com/ifrimSL360/starter`, work in a branch, and open a pull
request against `main`. The pull request is the review artifact. It should
contain your implementation, your tests, and a short note covering your design
decisions, the tradeoffs you accepted, and the AI suggestions you verified,
changed, or rejected. Do not include employer code or private chat transcripts.

## Files

- `src/main/java/interview/executor/RefundService.java` is the unfinished stage 1
  service.
- `src/main/java/interview/executor/RefundController.java` exposes the two
  endpoints. Its status codes are placeholders.
- `src/main/resources/db/migration/V1__create_refund_operations.sql` creates the
  initial schema. It contains no work queue and no outbox.
- `src/main/java/interview/executor/Stripe.java` and the provider classes are
  reserved for stage 2.
