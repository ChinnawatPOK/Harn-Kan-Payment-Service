# Harn Kan Payment Service

Payment Service implements the [project blueprint](Harn-Kan-Payment-Service-Blueprint.md) in the existing `com.harnkan:payment` Maven project. It owns payment reservations, payment status and deal refunds. It does not own deals, slots, users or notifications.

## Architecture

```text
Deal Service --gRPC--> Payment Service --JdbcTemplate--> MySQL
Client       --REST--> Payment Service --PaymentGateway--> Fake Gateway
```

Payment Service is a **gRPC server only**. There are no outbound Deal Service calls. REST runs on **8080**, gRPC on **9091**, and MySQL on **localhost:3306**, database **harnkan_payment**.

Java 21, Spring Boot 4.0.8, Maven, Spring Web MVC, Spring JDBC, MySQL, protobuf and gRPC are used. There is no JPA/Hibernate. The existing Boot 4 `spring-boot-starter-webmvc` is retained. A Spring-managed gRPC Java server starts and stops with the application; its port property is `grpc.server.port`.

## Start locally

Prerequisites: Java 21 and Docker with Compose (or an existing MySQL 8 database). The Maven Wrapper downloads Maven and dependencies on the first build. Fake Gateway itself does not require internet access.

1. Create local configuration:

   ```bash
   cp .env.example .env
   ```

   Edit `DB_PASSWORD` in `.env` to a local password. `.env` is ignored by Git. If `.env` already exists, keep it; do not overwrite existing credentials. Spring Boot and Docker Compose both read this file.

2. Start MySQL:

   ```bash
   docker compose up -d --wait
   ```

   Compose creates `harnkan_payment` and persists its data in a named volume. It binds MySQL to the loopback interface. The password initializes a **new** volume; changing `.env` does not change the password in an existing database.

3. Build, test and run:

   ```bash
   ./mvnw clean test
   ./mvnw spring-boot:run
   ```

   Windows: use `mvnw.cmd` in place of `./mvnw`. An installed Maven can run the same goals with `mvn`.

   Expected startup messages include `Tomcat started on port 8080` and `Payment gRPC server listening on 9091`. The app fails startup if MySQL cannot be reached or the schema cannot be initialized.

To package and run a standalone executable JAR:

```bash
./mvnw clean package
java -jar target/payment-0.0.1-SNAPSHOT.jar
```

Run from the repository directory to load its `.env`, or supply environment variables explicitly. Stop the app with Ctrl+C; `docker compose stop` stops MySQL without removing its data.

## Environment variables

| Variable | Default / purpose |
| --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/harnkan_payment` |
| `DB_USERNAME` | `root` |
| `DB_PASSWORD` | Required; no committed default password |
| `PAYMENT_GATEWAY_MODE` | `fake`; only the fake adapter is implemented in this phase |
| `STRIPE_SECRET_KEY` | Reserved configuration for the future Stripe adapter |
| `STRIPE_WEBHOOK_SECRET` | Reserved configuration for the future signed webhook |
| `SERVER_PORT` | Optional REST port override; default `8080` |
| `GRPC_SERVER_PORT` | Optional gRPC port override; default `9091` |

All application timestamps use **UTC**, stored as MySQL `DATETIME` / Java `LocalDateTime`. API timestamps are ISO-8601 without an offset; clients should interpret them as UTC. Set local SQL seed timestamps with `UTC_TIMESTAMP()`.

## Existing local MySQL

If MySQL already listens on 3306, do not start the Compose database. Use its credentials in `.env` and create the database:

```sql
CREATE DATABASE harnkan_payment;
```

At startup Spring executes [schema.sql](src/main/resources/schema.sql) with `CREATE TABLE IF NOT EXISTS`:

```sql
CREATE TABLE IF NOT EXISTS payments (
    id VARCHAR(32) PRIMARY KEY,
    deal_id VARCHAR(32) NOT NULL,
    participant_id VARCHAR(32) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    payment_gateway_ref VARCHAR(255),
    refund_gateway_ref VARCHAR(255),
    payout_gateway_ref VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'RESERVED',
    reserved_until DATETIME,
    is_confirmed_receipt BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

There are no additional indexes, foreign keys or check constraints. `deal_id` and `participant_id` are logical references. Existing tables are preserved, not migrated automatically.

## gRPC APIs

The source of truth is [payment.proto](src/main/proto/payment.proto). Maven generates protobuf messages under `com.harnkan.contract.payment` and `PaymentServiceGrpc` during `generate-sources`. Deal Service must mirror this exact contract independently and target `localhost:9091`.

| RPC | Request | Response |
| --- | --- | --- |
| `harnkan.payment.PaymentService/CreatePayment` | `deal_id`, `participant_id`, decimal string `amount` | `payment_id`, `status`, `reserved_until` |
| `harnkan.payment.PaymentService/RefundDeal` | `deal_id` | `refunded_count` |

IDs supplied by callers must contain 1–32 ASCII letters, digits, underscores or hyphens. Generated payment IDs are 32 lowercase hexadecimal characters. Amounts must be positive, fit `DECIMAL(10,2)` and have at most two decimal places; no floating-point conversion or silent rounding is used.

With `grpcurl` installed (server reflection is enabled):

```bash
grpcurl -plaintext localhost:9091 list

grpcurl -plaintext -d '{"dealId":"22222222222222222222222222222222","participantId":"33333333333333333333333333333333","amount":"60.00"}' \
  localhost:9091 harnkan.payment.PaymentService/CreatePayment

grpcurl -plaintext -d '{"dealId":"22222222222222222222222222222222"}' \
  localhost:9091 harnkan.payment.PaymentService/RefundDeal
```

Alternatively, pass `-import-path src/main/proto -proto payment.proto` before the host to use the local contract.

Errors map to `INVALID_ARGUMENT`, `ALREADY_EXISTS`, `NOT_FOUND`, `FAILED_PRECONDITION`, `UNAVAILABLE` and `INTERNAL`. Remote callers never receive stack traces or raw gateway errors.

## REST APIs

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/payments/{paymentId}/pay` | Start an existing, unexpired `RESERVED` payment |
| `GET` | `/api/payments/{paymentId}` | Read the client-facing payment state |

Use the payment ID returned by `CreatePayment`:

```bash
PAYMENT_ID=replace_with_returned_payment_id
curl -i "http://localhost:8080/api/payments/$PAYMENT_ID"
curl -i -X POST "http://localhost:8080/api/payments/$PAYMENT_ID/pay"
```

Pay response:

```json
{"paymentId":"...","status":"PAYMENT_PENDING","clientSecret":"fake_secret_..."}
```

Read response:

```json
{
  "paymentId":"...",
  "dealId":"...",
  "participantId":"...",
  "amount":"60.00",
  "status":"RESERVED",
  "reservedUntil":"2026-09-30T14:45:00",
  "confirmedReceipt":false
}
```

There is no card-number/CVV DTO. The pay endpoint requires no request body. Missing payments return 404; expired reservations, invalid states and duplicates return 409; gateway errors return 502; invalid input returns 400; unexpected failures return 500. A lock timeout returns 503 so callers can retry.

```json
{"code":"PAYMENT_EXPIRED","message":"Payment reservation has expired"}
```

## Payment lifecycle and retry behavior

```text
CreatePayment -> RESERVED (UTC now + 5 minutes; no gateway call)
RESERVED -> PAYMENT_PENDING (REST pay starts a gateway transaction)
PAYMENT_PENDING -> HELD (successful callback)
PAYMENT_PENDING -> FAILED (failed callback)
RESERVED -> FAILED (pay attempted at/after expiry)

RefundDeal:
  RESERVED / PAYMENT_PENDING -> FAILED (no external refund)
  HELD -> REFUND_PENDING -> REFUNDED
  REFUND_PENDING -> retry gateway refund -> REFUNDED
  FAILED / REFUNDED -> skip
```

`PAYOUT_PENDING` and `RELEASED` are reserved statuses; payout is outside this phase.

An active payment in `RESERVED`, `PAYMENT_PENDING`, `HELD` or `REFUND_PENDING` prevents another reservation for the same deal and participant. Expiry is checked when pay is requested; there is no scheduled expiry worker in this blueprint. Until an expired reservation is changed to `FAILED`, its `RESERVED` status still blocks a duplicate.

Mutations acquire a **MySQL session advisory lock per deal**, including the duplicate check and initial insert. This enforces the duplicate/refund rules across multiple app instances without changing the required table constraints. All protected SQL reuses the lock's connection; statements autocommit. No database transaction spans a gateway call. Locks are released in `finally`, including when validation or a gateway call fails. Concurrent unrelated deals can proceed independently. Lock names normalize ASCII case to match MySQL's default case-insensitive ID comparison.

`REFUND_PENDING` is persisted before calling the gateway. Gateway failure leaves it retryable; successful refunds save their reference and become `REFUNDED`. If one row fails, other rows are processed and the RPC returns `UNAVAILABLE`; retry skips rows already completed. `refunded_count` counts only rows newly marked `REFUNDED` in that successful invocation, not historical refunds.

Gateway implementations must deduplicate start requests using payment ID and full refunds using gateway payment reference, including retries after a process crash between gateway success and a database write. Fake references are deterministic across restarts. Repeated callback events cannot revert terminal states. Per the blueprint, a success event arriving after cancellation does not resurrect a `FAILED` payment; real gateway capture/cancellation reconciliation is part of the later Stripe integration.

## Fake Gateway and local seeds

Fake mode is the implemented phase and the default. It returns deterministic test references and client secrets, does not collect money and does not automatically simulate a success callback. Business service callback handlers are covered by tests; no unauthenticated callback endpoint is exposed.

For a local fake-mode demo, apply [scripts/seed.sql](scripts/seed.sql) after the application has initialized the schema. These are explicit test records, never loaded automatically:

```bash
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root harnkan_payment' < scripts/seed.sql
```

Read or pay the seeded reservation:

```bash
curl -i http://localhost:8080/api/payments/11111111111111111111111111111111
curl -i -X POST http://localhost:8080/api/payments/11111111111111111111111111111111/pay
```

Refund the seeded `HELD` payment (second identical call returns zero):

```bash
grpcurl -plaintext -d '{"dealId":"55555555555555555555555555555555"}' \
  localhost:9091 harnkan.payment.PaymentService/RefundDeal
```

The seed uses `INSERT IGNORE` to preserve existing records; rerunning it does not reset payment state or extend an old reservation.

## Stripe phase

As specified by the blueprint's “First implementation: FakePaymentGateway / Later: StripePaymentGateway”, Stripe SDK integration is deferred. `PAYMENT_GATEWAY_MODE=stripe` **fails startup explicitly**, rather than processing fake transactions under a real gateway setting. The Stripe configuration variables are reserved and never logged. `/api/payments/stripe/webhook` is not registered in fake mode.

Before enabling Stripe, implement `StripePaymentGateway` behind the existing interface, verify webhook signatures over the raw request body, add persistent gateway idempotency/reconciliation, and reconcile asynchronous refunds and late capture after cancellation. Only confirmed successful refunds may return `RefundResult`; a pending external refund must stay `REFUND_PENDING`. See [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests) and [webhook signature verification](https://docs.stripe.com/webhooks/signature).

The local APIs currently have no authentication/TLS, as neither is defined in this blueprint. Real-money deployment needs the service's access-control and gateway integration phase.

## Tests

```bash
# Unit, H2 JDBC mapping/SQL, in-process gRPC, REST MVC and application context tests
./mvnw clean test

# All tests plus a separate disposable MySQL 8.4 container, real network REST/gRPC,
# concurrency, expiry persistence and refund recovery (Docker required)
./mvnw -Pmysql-it verify
```

If Testcontainers cannot discover Docker Desktop on macOS, set `DOCKER_HOST` to the Docker context's socket, for example:

```bash
DOCKER_HOST=unix:///Users/your-user/.docker/run/docker.sock ./mvnw -Pmysql-it verify
```

Integration tests use a separate temporary database/container and do not modify the local `harnkan_payment` data. Reports are in `target/surefire-reports` and `target/failsafe-reports`.

Implementation references: [gRPC Java Maven generation](https://github.com/grpc/grpc-java#generated-code), [Boot 4 WebMvcTest](https://docs.spring.io/spring-boot/4.0/api/java/org/springframework/boot/webmvc/test/autoconfigure/WebMvcTest.html), and [MySQL advisory locking](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html).

### Compatibility notes against the blueprint

The blueprint requests JUnit 5 while also requiring the existing Spring Boot 4.0.8 version. Spring Framework 7's [SpringExtension requires JUnit Jupiter 6 or newer](https://docs.spring.io/spring-framework/docs/7.0.5/javadoc-api/org/springframework/test/context/junit/jupiter/SpringExtension.html), so this project keeps Boot's managed JUnit Jupiter 6.0.3. The tests use the familiar Jupiter annotations and Mockito. H2 and Testcontainers are test-only dependencies; the runtime database remains MySQL.
