# Harn Kan Payment Service

Payment reservations, Stripe sandbox/fake payments, cancellation, refunds, receipt confirmation and ledger/sandbox Connect transfers, following `api-design.md` sections 3.2 and 4. REST runs on **8080**, gRPC on **9091**, with MySQL database **payment**. Requires Java 21.

## Implemented APIs

| Code | REST endpoint | Purpose |
| --- | --- | --- |
| PY5 | `POST /api/payments/{paymentId}/pay` | Start an unexpired reservation; return `PAYMENT_PENDING` and a fake `clientSecret` |
| PY2 | `GET /api/payments/{paymentId}` | Read a payment; money is a JSON number and timestamps include UTC `Z` |
| PY1 | `POST /webhooks/stripe` | Receive signed Stripe snapshot events; no JWT required |
| PY4 | `POST /internal/dev/payments/{paymentId}/simulate-success` | Mark a pending fake payment `HELD`; requires explicit development opt-in |

Payment has no Spring Security filter chain, JWT decoder, or JWT secret. It does not inspect `Authorization` tokens or validate signatures, expiry, or claims. GET/pay read the caller ID directly from `X-User-Id` and still enforce ownership in the business service. Missing/blank `X-User-Id` returns 401 `USER_ID_REQUIRED`; a different owner returns 403. The service trusts the supplied header; validating caller identity belongs to the upstream caller/Gateway. This change does not implement a Gateway or change Deal/User authentication.

Stripe mode uses the official Stripe Java SDK to create THB PaymentIntents (baht × 100 satang) with `metadata.paymentId` and idempotency key `pay-<paymentId>`. `/pay` returns a `clientSecret` for the mobile PaymentSheet; it does not confirm the payment. Automatic payment methods are enabled with redirects excluded. The mobile app uses the sandbox's publishable key independently; backend credentials are never returned to it.

`POST /webhooks/stripe` exists only in Stripe mode and verifies the `Stripe-Signature` header over the exact raw body with a five-minute timestamp tolerance. It accepts `payment_intent.succeeded`, `payment_intent.payment_failed` and `payment_intent.canceled`; other signed events and unrelated sandbox payments are acknowledged without mutation. Stable snapshot fields are parsed after verification, so the CLI's `2020-03-02` event version is supported without a typed SDK event-version mismatch. Invalid/missing signatures or invalid payloads return 400; database/lock failures return an error so Stripe can retry. Status is committed before the direct Deal RPC; a Deal failure is logged and the webhook still returns 200.

Stripe refunds use the PaymentIntent and `refund-<paymentId>` as the idempotency key. Before creating a refund, the adapter reads any existing refund's current status, preserving retries even when a previous idempotent response said `pending`. Only `succeeded` becomes REFUNDED; pending/failed/requires-action refunds stay REFUND_PENDING. A permanently failed/canceled provider refund requires investigation rather than silently creating another refund. Payout defaults to the deterministic ledger. The optional `sandbox-transfer` mode calls Stripe Connect with test keys only; it does not create bank payouts.

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
- Per-deal MySQL advisory locks serialize mutations across instances. Gateway calls use deterministic identities so retries after a crash do not create another fake refund/payout.
- `RefundDeal` returns partial progress, including `REFUND_PENDING`, instead of discarding the response on a gateway failure. `refunded_count` counts newly refunded rows. Deal's scheduler owns refund retries.
- `ReleaseFunds` rejects unconfirmed or unpaid participants, excluding FAILED/REFUNDED rows. Failed payouts remain PAYOUT_PENDING; retry skips RELEASED rows. `released_count` and `total_amount` include all released rows on every retry, making completion and totals stable. Deal validates and supplies the host ID for each internal payout request.
- Once any payout starts, `RefundDeal` rejects the deal. Deal is responsible for enforcing its cancellation/purchase rules before calling Payment.
- Duplicate/out-of-order callbacks do not reverse completed states. Internal gateway-result handlers refund a late success after cancellation rather than resurrecting the participant. Stripe webhooks apply these same transition rules.

The receipt policy follows the proposed defaults in specification section 9: wait for all participants to confirm, no auto-confirm. Ledger remains the default; the requested sandbox Connect transfer mode is optional. Host does not get a payment reservation; Deal passes only paying participants to Payment and supplies the host ID when releasing funds.

## Deal integration and events

Payment calls `harnkan.deal.DealService/UpdateParticipantPayment` with `deal_id`, `participant_id`, `payment_id`, `status` (PAYMENT_PENDING / HELD / FAILED). The local client contract is [deal.proto](src/main/proto/deal.proto).

Pay, reservation expiry on pay, simulation and gateway-result handlers persist the payment state, release its mutation lock, then call Deal directly. Failures are logged without undoing the payment or failing Stripe's webhook. Owner-authorized GET resends the current PAYMENT_PENDING/HELD/FAILED state, allowing Deal to recover through reads without a delivery table. **RPCs initiated by Deal never call Deal back**; their responses carry the state instead.

The neighboring Deal repository currently provides `ConfirmSlot`/`ReleaseSlot`, not the new `UpdateParticipantPayment` RPC. It must implement specification section 3.2 and update its Payment proto before the full application flow works. Until then, direct notifications fail and are logged; owner GET can resend them after Deal supports the contract. This change does not modify the Deal repository.

Payment publishes persistent JSON events to the durable topic exchange `harnkan.events`:

| Routing key | Payload fields (besides event envelope) |
| --- | --- |
| `payment.refunded` | `dealId`, `paymentId`, `participantId`, `amount` |
| `payout.released` | `dealId`, `creatorId`, `totalAmount` |

The envelope contains `eventId`, `eventType`, `occurredAt` (UTC). Amounts in events are decimal strings. The refund payload/envelope follows the local Deal event convention; the referenced `notification-rabbitmq-spec.md` is absent from this workspace, so its exact consumer contract has not been verified. Coordinate this payload with Notification Service.

Only the original V1 `payments` schema is used, plus Flyway's own history table. There is no delivery table, event outbox or payout-recipient table. Refund/payout progress and gateway references remain in `payments`. Events are sent directly to RabbitMQ after status is saved, using publisher confirms. A failed or unroutable event is logged and is not stored for replay; it does not roll back a successful refund/payout. Retrying a completed refund/payout does not publish another event. Notification owns its queues/bindings and should deduplicate by `eventId`.

If a local database previously applied the removed experimental V2, back up that database before removing its three auxiliary tables and only its V2 Flyway-history row. Keep the V1 history and all `payments` rows intact; do not disable Flyway validation. A database already cleaned this way validates against V1 normally.

Deal owns reservation/deal-expiry scheduling and refund/payout retries (specification section 3.3). Payment also checks Stripe every minute for PAYMENT_PENDING rows unchanged for over ten minutes, and retries REFUND_PENDING rows (including automatic refunds for late successes). Owner-authorized GET can reconcile the same states, plus older FAILED rows with a gateway reference. Provider outages leave the persisted status readable; repair retries later. Deal and Payment retry paths share the per-deal lock and provider identities.

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

For Payment + Deal + MySQL, run `docker compose -f docker-compose-all-java.yml up -d --build`. This Compose file uses an external RabbitMQ broker at `host.docker.internal:5672`; it does not create a RabbitMQ container or volume. Override `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, and `RABBITMQ_PASSWORD` in local `.env` for the shared broker. Set `DEAL_REST_PORT` in local `.env` if port 8081 is already in use (for example, `8083` while Expo Metro uses 8081).

The existing `docker-compose.yml` runs Payment against MySQL on the host; it does not provision a database, Deal or RabbitMQ. `docker compose up --build -d` builds and starts Payment. Container-to-host dependencies default to `host.docker.internal`; override the targets when joining a shared Compose network.

| Environment variable | Default / purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | Local MySQL database `payment` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Local development datasource credentials |
| `PAYMENT_GATEWAY_MODE` | `fake` by default; `stripe` in the configured local `.env` |
| `PAYMENT_DEV_ENABLED` | `false`; set `true` to enable fake simulation |
| `DEAL_GRPC_TARGET` | `localhost:9090` when running directly |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | RabbitMQ credentials |
| `SERVER_PORT` / `GRPC_SERVER_PORT` | `8080` / `9091` |
| `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` | Required in Stripe mode; startup fails if either is missing |
| `STRIPE_PUBLISHABLE_KEY` | Saved in local `.env` for mobile configuration; not consumed/exposed by backend |

Gateway must block `/internal/**`. The simulator requires the explicit fake/dev configuration; do not enable it on a public instance.

## Try the fake flow

Create a reservation using gRPC (reflection is enabled):

```bash
grpcurl -plaintext -d '{"dealId":"22222222222222222222222222222222","participantId":"333333333333333333333333","amount":"189.00"}' \
  localhost:9091 harnkan.payment.PaymentService/CreatePayment
```

Set `PAYMENT_ID` to the returned ID and `USER_ID` to the reservation's `participantId`:

```bash
curl -H "X-User-Id: $USER_ID" "http://localhost:8080/api/payments/$PAYMENT_ID"
curl -X POST -H "X-User-Id: $USER_ID" "http://localhost:8080/api/payments/$PAYMENT_ID/pay"
curl -X POST -H "X-User-Id: $USER_ID" "http://localhost:8080/internal/dev/payments/$PAYMENT_ID/simulate-success"
```

Deal normally calls ConfirmReceipt and ReleaseFunds. To test directly with grpcurl, send `paymentId` + `participantId` to ConfirmReceipt, then `dealId` + `creatorId` to ReleaseFunds. Both are internal RPCs.

REST errors include `code`, `message`, `timestamp`: 400 invalid input/webhook signature, 401 missing caller ID header, 403 wrong owner, 404 missing payment or disabled dev simulator, 409 invalid state/expired reservation, 502 gateway failure, 503 lock timeout. gRPC maps these to INVALID_ARGUMENT, PERMISSION_DENIED, NOT_FOUND, ALREADY_EXISTS, FAILED_PRECONDITION, UNAVAILABLE or INTERNAL.

## Verification

```bash
./mvnw clean test
./mvnw -DskipTests package
```

Tests require Docker. Testcontainers starts isolated MySQL 8.4 and RabbitMQ containers; it does not touch the configured local database. Tests exercise actual REST and gRPC, header-based owner checks without JWT validation, expiry persistence, concurrent duplicate reservations, refund/payout failures and retries, receipt idempotency, late callbacks, direct Deal updates/recovery via GET, lock ordering, and RabbitMQ publishing/failure handling, and a schema check that only payments/Flyway tables exist. The external Deal client is mocked; real cross-service compatibility is not claimed.

Stripe-specific tests exercise the SDK against a local HTTP fixture, raw-body signatures and tampering, old-version snapshots, mode selection, reconciliation and refund state handling. Normal tests never call Stripe or load the local secrets. An explicit sandbox smoke test is available:

```bash
RUN_STRIPE_SANDBOX_TEST=true ./mvnw -Dtest=StripeSandboxIT test
```

This opt-in test uses only `sk_test_`/`rk_test_` keys from the environment or local `.env`, creates a THB test payment, confirms it with `pm_card_visa`, and refunds it. It also verifies repeat starts/refunds are idempotent. It does not charge real money.

Implementation references: [Stripe Java SDK](https://github.com/stripe/stripe-java), [PaymentIntent creation](https://docs.stripe.com/api/payment_intents/create?lang=java), [refunds](https://docs.stripe.com/api/refunds/create?lang=java), [webhook signature verification](https://docs.stripe.com/events/manage-webhook-endpoints#signature-errors).

## Optional Stripe sandbox host transfer

`ReleaseFunds(deal_id, creator_id)` can transfer each confirmed payment to one shared sandbox destination for every creator. Set `STRIPE_PAYOUT_MODE=sandbox-transfer` and `STRIPE_SANDBOX_HOST_ACCOUNT_ID=acct_...`. The creator ID is kept in transfer metadata, but does not select the destination. Startup rejects live keys or a missing destination account in this mode; it never silently substitutes a fake transfer. `ledger` remains the default until these settings are supplied.

Configure an existing test connected account in `.env` (the shared destination is used for all creators):

```dotenv
STRIPE_PAYOUT_MODE=sandbox-transfer
STRIPE_SANDBOX_HOST_ACCOUNT_ID=acct_replace_with_test_account
```

Create Deal does not create a Stripe account, query a creator-account mapping, or open bank/onboarding forms. Configure and onboard the shared test account separately in Stripe. No creator-account table is required. The optional `scripts/create-sandbox-host.py --host-user-id test-host` helper can create/reuse a test account and configure this same shared destination; its host argument labels the test account only. Restart/rebuild Payment after changing configuration.

The existing flow stays: pay → Stripe webhook → HELD → ConfirmReceipt for everyone → ReleaseFunds. In sandbox-transfer mode, the adapter verifies the original test PaymentIntent, links the transfer to its paid/unrefunded charge, and records `tr_...` in the existing `payout_gateway_ref`. No new table or HTTP endpoint is added. Fake gateway mode still uses a ledger.

Transfers are strictly THB. The original PaymentIntent and its source charge's balance transaction must both be THB, with the same gross amount. USD or another settlement currency is rejected before any transfer is created; the gateway does not convert to USD or silently use another currency. A sandbox platform/account configuration that supports THB settlement and THB transfers is required. A US Express test account has been created manually; the supplied response showed inactive transfers and USD as its default currency. A successful THB transfer has not yet been verified. Original THB amount/currency are included in transfer metadata. Platform fees remain the platform's responsibility. `RELEASED` means a transfer to the host's Stripe balance was created; settlement can still be pending and this does not indicate a bank payout.

The adapter uses `release-<paymentId>` for request idempotency and checks existing transfers in `payment_<paymentId>` before creating a new one, including retries after the idempotency retention window. A mismatched recipient, refunded payment, reversed transfer or API failure is not marked RELEASED. Payment keeps PAYOUT_PENDING on gateway errors so ReleaseFunds can be retried.
