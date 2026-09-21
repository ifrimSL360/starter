# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A timed backend interview exercise (fork of `ifrimSL360/starter`): an idempotent
refund service on Spring Boot 3.4 + JDBC + Flyway + PostgreSQL. `README.md` is
stage 1 and is the authoritative requirement list — read it before changing
anything. The interviewer adds stage 2 (async provider processing, durable work,
retries, crash recovery) mid-session.

**Do not implement stage 2 work before that requirement arrives**: no worker, no
work queue, no outbox, no provider call. Stage 1 must leave
`GET /actuator/demo-stripe` at `attempts: 0`.

The deliverable is a pull request whose design argument is what gets assessed;
the tests support it.

## Commands

```sh
docker compose up -d --wait postgres     # local dev database only
./mvnw -B test                           # full test suite (Testcontainers, own container)
./mvnw -B test -Dtest=StarterSmokeTest   # single test class
./mvnw -B test -Dtest=RefundTest#methodName
./mvnw -B spring-boot:run                # http://localhost:8080
docker compose down -v                   # reset the dev database
```

Tests need a running Docker daemon but not the compose database — Testcontainers
starts its own PostgreSQL. If port 5432 is taken, change the published port and
the JDBC URL together (`POSTGRES_PORT=5434 docker compose up -d --wait postgres`
with `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/commands`).

Java 17 is the target. Do not use APIs newer than Java 17 — the compiler rejects
them even on a newer JDK.

## Architecture

Single Maven module, package `interview.executor`, plus one auto-configuration
package.

- `RefundController` — the two endpoints; its status codes are **placeholders**
  (always 202/200) and must be replaced to match the README's contract table.
- `RefundService` — the unimplemented stage 1 seam. Holds a `JdbcTemplate`
  directly; there is no repository layer, and no JPA on the classpath.
- `V1__create_refund_operations.sql` — the whole schema. The unique constraint
  `refund_operation_customer_payment_uk (customer_id, payment_id)` is the point
  of the exercise: the database, not the application, decides who wins a
  duplicate creation. Check constraints on amount, currency length, and status
  mirror the validation rules.
- `Stripe` / `StripeException` / `RefundRequest` — the provider seam, reserved
  for stage 2. **The `Stripe` interface and its method signature must not
  change**; the interview checks depend on it. `StripeException.Outcome`
  distinguishes `DEFINITELY_NOT_EXECUTED` from `UNKNOWN`, which matters for
  stage 2 retry safety.
- `interview.autoconfigure.ProviderAutoConfiguration` — registers `DemoStripe`
  and a `Clock` under `@ConditionalOnMissingBean`, deliberately as an
  auto-configuration (registered via `META-INF/spring/...AutoConfiguration.imports`)
  so it runs after application beans and backs off when a test or the
  application declares its own `Stripe` bean.
- `DemoStripeEndpoint` — actuator endpoint `demo-stripe`, exposed in
  `application.yml`, used to assert the provider was never called.

### Invariants that drive the design

- Operation identity is the `(customer_id, payment_id)` pair; the same payment
  under a different customer is a different operation.
- The service supplies the idempotency key (the operation UUID); clients never
  send one and must treat the ID as opaque.
- Correctness must not depend on JVM-local state: several instances share one
  database, so duplicate resolution belongs in the insert (e.g. `on conflict`
  returning the existing row), not in a check-then-insert in Java.
- `X-Customer-Id` is authenticated-but-untrusted input at the HTTP boundary.
- Another customer's operation gets 404, never 403, and the body must not leak
  the operation ID, refund ID, or request data.

## Tests

Live in `src/test/java/interview/executor` alongside the fixtures the exercise
ships:

- `PostgresTestBase` — one container per test JVM, wired in via
  `@DynamicPropertySource`. Extend it from a `@SpringBootTest` for any database
  test. It pins `api.version=1.41` for docker-java compatibility.
- `FakeStripe` — provider fake modelling provider idempotency, with attempt and
  refund counters, one-shot failure injection (`failNext`) and a `CallGate` for
  deterministic blocking. Install it as a `@TestConfiguration` `Stripe` bean to
  displace `DemoStripe`. Stage 1 tests only need its counters.
- `StarterSmokeTest` — keep green; it proves Flyway applied the schema.

Coordinate concurrency tests with deterministic gates and bounded waits, never
`sleep`. Acceptance case 8 (concurrent duplicate submits through one instance)
is what exposes a check-then-insert race, so it is the test that justifies the
database-side design.
