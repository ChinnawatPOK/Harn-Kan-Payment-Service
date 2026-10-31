# Harn Kan Payment Service

Payment reservations, Stripe sandbox/fake payments, cancellation, refunds, receipt confirmation and ledger payouts, following `api-design.md` sections 3.2 and 4. REST runs on **8080**, gRPC on **9091**, with MySQL database **payment**. Requires Java 21.

## Implemented APIs

| Code | REST endpoint | Purpose |
| --- | --- | --- |
| PY5 | `POST /api/payments/{paymentId}/pay` | Start an unexpired reservation; return `PAYMENT_PENDING` and a fake `clientSecret` |
| PY2 | `GET /api/payments/{paymentId}` | Read a payment; money is a JSON number and timestamps include UTC `Z` |
| PY1 | `POST /webhooks/stripe` | Receive signed Stripe snapshot events; no JWT required |
| PY4 | `POST /internal/dev/payments/{paymentId}/simulate-success` | Mark a pending fake payment `HELD`; requires explicit development opt-in |

Client REST endpoints and the development simulator require a User Service HS256 JWT with string `sub` and `exp`. GET/pay enforce ownership using the verified JWT subject. Client-supplied `X-User-Id` is ignored. JWT verification stays in Payment until the coordinated Gateway migration described in specification section 7; it has not been removed.

Stripe mode uses the official Stripe Java SDK to create THB PaymentIntents (baht × 100 satang) with `metadata.paymentId` and idempotency key `pay-<paymentId>`. `/pay` returns a `clientSecret` for the mobile PaymentSheet; it does not confirm the payment. Automatic payment methods are enabled with redirects excluded. The mobile app uses the sandbox's publishable key independently; backend credentials are never returned to it.

`POST /webhooks/stripe` exists only in Stripe mode and verifies the `Stripe-Signature` header over the exact raw body with a five-minute timestamp tolerance. It accepts `payment_intent.succeeded`, `payment_intent.payment_failed` and `payment_intent.canceled`; other signed events and unrelated sandbox payments are acknowledged without mutation. Stable snapshot fields are parsed after verification, so the CLI's `2020-03-02` event version is supported without a typed SDK event-version mismatch. Invalid/missing signatures or invalid payloads return 400; database/lock failures return an error so Stripe can retry. Status changes enqueue durable Deal updates; the webhook does not wait for Deal.

Stripe refunds use the PaymentIntent and `refund-<paymentId>` as the idempotency key. Before creating a refund, the adapter reads any existing refund's current status, preserving retries even when a previous idempotent response said `pending`. Only `succeeded` becomes REFUNDED; pending/failed/requires-action refunds stay REFUND_PENDING. A permanently failed/canceled provider refund requires investigation rather than silently creating another refund. Payout remains a deterministic simulated ledger and does not call Stripe Connect.

The source of truth for Payment's gRPC server is [payment.proto](src/main/proto/payment.proto):

| RPC | Request | Result / behavior |
| --- | --- | --- |
| `CreatePayment` | `deal_id`, `participant_id`, decimal string `amount` | `payment_id`, `status`, `reserved_until`; creates a five-minute reservation |
| `CancelPayment` | `payment_id` | `payment_id`, `status`; RESERVED/PAYMENT_PENDING → FAILED; repeating FAILED is a no-op |
| `RefundDeal` | `deal_id` | `refunded_count`, `payments[]`; unpaid → FAILED, HELD/REFUND_PENDING → refund retry |
| `ConfirmReceipt` | `payment_id`, `participant_id` | `payment_id`, `status`, `is_confirmed_receipt`; validates owner and HELD, sets flag; safe to retry after payout |
| `ReleaseFunds` | `deal_id`, `creator_id` | `released_count`, `total_amount`, `payments[]`; confirmed HELD/PAYOUT_PENDING → RELEASED |

RPC paths use service `harnkan.payment.PaymentService`. `payments[]` items contain `payment_id`, `participant_id`, `status`. Existing protobuf field numbers are preserved. Callers must update their proto copies to consume the new RPCs and response fields. gRPC uses a trusted internal network, with no JWT, and must not be exposed publicly.

## State and retry behavior

```text
CreatePayment -> RESERVED -> pay -> PAYMENT_PENDING -> simulate-success -> HELD
RESERVED / PAYMENT_PENDING -> cancel / expiry / failure -> FAILED
HELD -> RefundDeal -> REFUND_PENDING -> REFUNDED
HELD -> ConfirmReceipt -> HELD (is_confirmed_receipt = true)
HELD (all confirmed) -> ReleaseFunds -> PAYOUT_PENDING -> RELEASED
```

- Payment does not have a `RECEIVED` state; that is a Deal participant state.
- Duplicate active reservations are rejected with `ALREADY_EXISTS`. Active includes payout states, preventing a second reservation after release.
- Per-deal MySQL advisory locks serialize mutations across instances. Database-only transactions atomically save a status and its delivery record. Gateway calls use deterministic identities so retries after a crash do not create another fake refund/payout.
- `RefundDeal` returns partial progress, including `REFUND_PENDING`, instead of discarding the response on a gateway failure. `refunded_count` counts newly refunded rows. Deal's scheduler owns refund retries.
- `ReleaseFunds` rejects unconfirmed or unpaid participants, excluding FAILED/REFUNDED rows. Failed payouts remain PAYOUT_PENDING; retry skips RELEASED rows. `released_count` and `total_amount` include all released rows on every retry, making completion and totals stable. A payout recipient is bound on the first attempt and cannot change on retry.
- Once any payout starts, `RefundDeal` rejects the deal. Deal is responsible for enforcing its cancellation/purchase rules before calling Payment.
- Duplicate/out-of-order callbacks do not reverse completed states. Internal gateway-result handlers refund a late success after cancellation rather than resurrecting the participant. Stripe webhooks apply these same transition rules.

The payout policy follows the **proposed defaults** in specification section 9: ledger simulation, wait for all participants to confirm, no auto-confirm. These policies still require team agreement. Host does not get a payment reservation; Deal passes only paying participants to Payment and supplies the host ID when releasing funds.

## Deal integration and reliable delivery

Payment calls `harnkan.deal.DealService/UpdateParticipantPayment` with `deal_id`, `participant_id`, `payment_id`, `status` (PAYMENT_PENDING / HELD / FAILED). The local client contract is [deal.proto](src/main/proto/deal.proto).

Pay, reservation expiry on pay, simulation and gateway-result handlers queue a Deal update in the same database transaction as the status change, then attempt delivery after releasing the payment mutation lock. A worker retries failures every five seconds; the latest state survives restarts. A separate delivery lock serializes concurrent sends without blocking Deal-initiated payment mutations. **RPCs initiated by Deal never call Deal back**; their responses carry the state instead.

The neighboring Deal repository currently provides `ConfirmSlot`/`ReleaseSlot`, not the new `UpdateParticipantPayment` RPC. It must implement specification section 3.2 and update its Payment proto before the full application flow works. Until then, Payment keeps retrying queued updates. This change does not modify the Deal repository.

Payment publishes persistent JSON events to the durable topic exchange `harnkan.events`:

| Routing key | Payload fields (besides event envelope) |
| --- | --- |
| `payment.refunded` | `dealId`, `paymentId`, `participantId`, `amount` |
| `payout.released` | `dealId`, `creatorId`, `totalAmount` |

The envelope contains `eventId`, `eventType`, `occurredAt` (UTC). Amounts in events are decimal strings. The refund payload/envelope follows the local Deal event convention; the referenced `notification-rabbitmq-spec.md` is absent from this workspace, so its exact consumer contract has not been verified. Coordinate this payload with Notification Service.

Migration V2 adds durable Deal delivery, event outbox and payout-recipient tables; V1 and the existing payments table are unchanged. Events are acknowledged in the database only after RabbitMQ publisher confirmation and successful routing. Failed/unroutable events remain pending. Delivery is at least once: consumers must deduplicate by `eventId`. Notification owns queues/bindings; if no queue is bound, the event stays pending. Database delivery records are retained after successful event delivery to prevent duplicate event creation on retries.

Deal owns reservation/deal-expiry scheduling and refund/payout retries (specification section 3.3). Payment also checks Stripe every minute for PAYMENT_PENDING rows unchanged for over ten minutes, and retries REFUND_PENDING rows (including automatic refunds for late successes). Owner-authorized GET can reconcile the same states, plus older FAILED rows with a gateway reference. Provider outages leave the persisted status readable; repair retries later. Deal and Payment retry paths share the per-deal lock and provider identities.

Payment's delivery worker independently retries outbound Deal updates and RabbitMQ events.

## Run locally

Provide an existing MySQL 8 instance with database `payment`; Flyway applies migrations automatically. Configure datasource credentials using Spring environment variables. Run RabbitMQ and a Deal service implementing the new contract for full integration.

Spring Boot reads the repository's local `.env` automatically (optional Spring config import); shell environment variables override it. `.env` is excluded from Git and Docker build contexts. `.env.example` contains placeholders for a new machine. The supplied local configuration uses Stripe sandbox mode; no credentials are stored in source files.

```bash
# From the repository, using the local .env:
./mvnw spring-boot:run
```

In another terminal, authorize Stripe CLI for the same sandbox and leave the listener running:

```bash
stripe login
stripe listen --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled --forward-to localhost:8080/webhooks/stripe
```

Set `STRIPE_WEBHOOK_SECRET` in local `.env` to the listener's signing secret and restart Payment after changing it. CLI signing secrets and Dashboard endpoint secrets are distinct. A local CLI listener does not require a Dashboard webhook URL. For deployed servers, register the HTTPS endpoint and use that endpoint's signing secret.

To use fake mode instead:

```bash
PAYMENT_GATEWAY_MODE=fake PAYMENT_DEV_ENABLED=true ./mvnw spring-boot:run
```

The existing `docker-compose.yml` runs Payment against MySQL on the host; it does not provision a database, Deal or RabbitMQ. `docker compose up --build -d` builds and starts Payment. Container-to-host dependencies default to `host.docker.internal`; override the targets when joining a shared Compose network.

| Environment variable | Default / purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | Local MySQL database `payment` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Local development datasource credentials |
| `PAYMENT_JWT_SECRET` | Must match User Service; overrides the existing local development default |
| `PAYMENT_GATEWAY_MODE` | `fake` by default; `stripe` in the configured local `.env` |
| `PAYMENT_DEV_ENABLED` | `false`; set `true` to enable fake simulation |
| `DEAL_GRPC_TARGET` | `localhost:9090` when running directly |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | RabbitMQ credentials |
| `PAYMENT_DELIVERY_ENABLED` | `true`; disables scheduled delivery only, not immediate Deal attempts |
| `SERVER_PORT` / `GRPC_SERVER_PORT` | `8080` / `9091` |
| `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` | Required in Stripe mode; startup fails if either is missing |
| `STRIPE_PUBLISHABLE_KEY` | Saved in local `.env` for mobile configuration; not consumed/exposed by backend |

Gateway must block `/internal/**`. The simulator also requires a valid JWT and the explicit fake/dev configuration; do not enable it on a public instance.

## Try the fake flow

Create a reservation using gRPC (reflection is enabled):

```bash
grpcurl -plaintext -d '{"dealId":"22222222222222222222222222222222","participantId":"333333333333333333333333","amount":"189.00"}' \
  localhost:9091 harnkan.payment.PaymentService/CreatePayment
```

Set `PAYMENT_ID` to the returned ID and `ACCESS_TOKEN` to a User Service JWT whose `sub` matches `participantId`:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" "http://localhost:8080/api/payments/$PAYMENT_ID"
curl -X POST -H "Authorization: Bearer $ACCESS_TOKEN" "http://localhost:8080/api/payments/$PAYMENT_ID/pay"
curl -X POST -H "Authorization: Bearer $ACCESS_TOKEN" "http://localhost:8080/internal/dev/payments/$PAYMENT_ID/simulate-success"
```

Deal normally calls ConfirmReceipt and ReleaseFunds. To test directly with grpcurl, send `paymentId` + `participantId` to ConfirmReceipt, then `dealId` + `creatorId` to ReleaseFunds. Both are internal RPCs.

REST errors include `code`, `message`, `timestamp`: 400 invalid input/webhook signature, 401 invalid/missing JWT, 403 wrong owner, 404 missing payment or disabled dev simulator, 409 invalid state/expired reservation, 502 gateway failure, 503 lock timeout. gRPC maps these to INVALID_ARGUMENT, PERMISSION_DENIED, NOT_FOUND, ALREADY_EXISTS, FAILED_PRECONDITION, UNAVAILABLE or INTERNAL.

## Verification

```bash
./mvnw clean test
./mvnw -DskipTests package
```

Tests require Docker. Testcontainers starts isolated MySQL 8.4 and RabbitMQ containers; it does not touch the configured local database. Tests exercise actual REST and gRPC, owner checks, expiry persistence, concurrent duplicate reservations, refund/payout failures and retries, receipt idempotency, late callbacks, durable Deal updates, lock ordering, and RabbitMQ confirmed/unroutable delivery. The external Deal client is mocked; real cross-service compatibility is not claimed.

Stripe-specific tests exercise the SDK against a local HTTP fixture, raw-body signatures and tampering, old-version snapshots, mode selection, reconciliation and refund state handling. Normal tests never call Stripe or load the local secrets. An explicit sandbox smoke test is available:

```bash
RUN_STRIPE_SANDBOX_TEST=true ./mvnw -Dtest=StripeSandboxIT test
```

This opt-in test uses only `sk_test_`/`rk_test_` keys from the environment or local `.env`, creates a THB test payment, confirms it with `pm_card_visa`, and refunds it. It also verifies repeat starts/refunds are idempotent. It does not charge real money.

Implementation references: [Stripe Java SDK](https://github.com/stripe/stripe-java), [PaymentIntent creation](https://docs.stripe.com/api/payment_intents/create?lang=java), [refunds](https://docs.stripe.com/api/refunds/create?lang=java), [webhook signature verification](https://docs.stripe.com/events/manage-webhook-endpoints#signature-errors).
