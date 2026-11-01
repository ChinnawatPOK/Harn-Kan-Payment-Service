# Stripe sandbox transfers

All creators use one configured test Connected Account. No Create Deal onboarding, creator account table or bank payout is added.

## Configuration

Set these in ignored `.env` for Docker Compose (Compose passes the values into the container):

```dotenv
PAYMENT_GATEWAY_MODE=stripe
STRIPE_SECRET_KEY=sk_test_replace_me
STRIPE_PAYOUT_MODE=sandbox-transfer
STRIPE_SANDBOX_HOST_ACCOUNT_ID=acct_1UWXRNLoV2Vrct9W
STRIPE_TEST_TRANSFER_ENABLED=true
```

The test endpoint defaults to disabled. Enabling it requires Stripe mode, sandbox-transfer mode, a test key, and a destination account. Live keys are rejected for transfers. When running Java directly, export these variables into the process; `.env` is not automatically loaded by this snapshot. Restart/rebuild after changing config. Default REST port is 8080; `SERVER_PORT` can override it. An existing reachable MySQL database is required by the application. Tests use isolated local fixtures and do not require Stripe credentials.

## Normal release

`PaymentService.ReleaseFunds(deal_id, creator_id)` on gRPC 9091 checks paid payments have confirmed receipts, invokes the Stripe adapter, and stores the returned `tr_...` in the existing payout reference column. RELEASED rows are skipped on retry. Stripe mode never returns a fake payout when transfer configuration is missing. Fake mode remains a ledger.

## Direct test endpoint

```bash
curl -X POST 'http://localhost:8080/internal/dev/stripe/transfers' \
  -H 'Content-Type: application/json' \
  -d '{
    "reference": "PAYMENT_ID",
    "creatorId": "test-user-123",
    "paymentIntentId": "pi_REPLACE_WITH_PAID_INTENT_ID",
    "amount": 189.00
  }'
```

Replace PAYMENT_ID with the exact `metadata.paymentId` on the paid Stripe PaymentIntent. Use the PaymentIntent ID (`pi_...`), never its client secret. Amount is in baht and must match the full original payment. The adapter converts it to satang. Every creator uses the destination from config; the caller cannot choose another account. This endpoint is a direct sandbox-provider test: it bypasses receipt confirmation and does not update Payment/Deal database rows. Use a dedicated test payment for it. No JWT is required for this explicitly enabled internal endpoint; call it directly on the service's local/internal port.

Success response:

```json
{
  "transferId": "tr_...",
  "destination": "acct_...",
  "currency": "usd",
  "amount": 5.60
}
```

The original PaymentIntent must be succeeded, test-mode, THB, and tagged with the matching payment ID. Its latest charge must be paid/unrefunded. Transfers accept THB or USD settlement, using the source balance transaction currency and gross minor-unit amount from Stripe. For example, a 189 THB charge settled as 560 USD cents transfers 5.60 USD, never 189 USD. THB settlement still requires an exact original amount match; unsupported currencies or missing/zero settlements are rejected. Request amount and payments.amount remain THB; response currency/amount describe the actual transfer. Original THB values are retained in Stripe transfer metadata. Fees remain the platform responsibility. No local FX rate is used.

The transfer uses `source_transaction=ch_...`, `transfer_group=payment_<reference>` and idempotency key `release-<reference>`. Repeated calls retrieve the existing group transfer and validate amount, creator, source and destination. Changed/reversed transfers or provider failures never produce fake success. Reference must remain stable for the same source payment.

## Scope of this restoration

The Stripe gateway also implements its required start-payment/refund operations. The current snapshot has no signed Stripe webhook controller, so this change alone does not restore a complete checkout-to-HELD flow. The direct transfer test operates on an already paid Stripe sandbox PaymentIntent. A bank payout API is not called.
