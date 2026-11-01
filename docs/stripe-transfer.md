# Stripe sandbox deal transfer

Every creator uses the one test Connected Account configured by STRIPE_SANDBOX_HOST_ACCOUNT_ID. No creator-account mapping table, Create Deal onboarding or bank payout is added.

## Configuration

```dotenv
PAYMENT_GATEWAY_MODE=stripe
STRIPE_SECRET_KEY=sk_test_replace_me
STRIPE_PAYOUT_MODE=sandbox-transfer
STRIPE_SANDBOX_HOST_ACCOUNT_ID=acct_1UWXRNLoV2Vrct9W
STRIPE_TEST_TRANSFER_ENABLED=true
SERVER_PORT=8080
```

Compose reads ignored .env and passes settings to Payment. For a direct Java launch export them into the process. The internal test endpoint is disabled by default, requires explicit sandbox configuration, and requires no JWT on the local/internal service port. Live keys are rejected by the transfer service.

## One transfer per deal

Both gRPC PaymentService.ReleaseFunds(deal_id, creator_id) on 9091 and the test REST endpoint use DealPayoutService. Every paid payment must have its receipt confirmed. Unresolved reservations, pending payments and pending refunds prevent release. FAILED/REFUNDED rows are excluded. The backend reads the payment list and original THB amounts from the DB; clients do not send a total or PaymentIntent list.

StripeTransferService validates every original test PaymentIntent against its DB payment ID and THB amount, then sums the paid/unrefunded charges' gross settlement balance transactions. USD or THB settlement is supported, but a deal cannot mix settlement currencies. For ten 189 THB payments each settled as 5.60 USD, the original deal total is 1890.00 THB and the one Stripe Transfer is 56.00 USD. No local exchange rate is calculated.

The aggregate transfer has no source_transaction: one source charge cannot fund the entire deal. It debits the platform's available Stripe balance; insufficient funds leave payments PAYOUT_PENDING and require a later retry. Stripe fees are the platform's responsibility.

## Test curl

```bash
curl -X POST 'http://localhost:8080/internal/dev/stripe/transfers' \
  -H 'Content-Type: application/json' \
  -d '{
    "dealId": "REPLACE_WITH_DEAL_ID",
    "creatorId": "REPLACE_WITH_CREATOR_USER_ID"
  }'
```

This is a real sandbox release: it enforces receipt confirmation and updates the existing payment rows. It no longer accepts the old single-payment reference/paymentIntentId/amount request. It does not alter receipts, invent payments, or create a bank payout.

The response carries releasedCount, totalAmount and payments. totalAmount is the original THB business total, not the USD amount sent to Stripe. Each released row has the same paymentTransferGatewayRef tr_...; view that Transfer in Stripe for its settlement currency and amount.

## Existing column and duplicate protection

No table is added. Migration V3 adds payments.payment_transfer_gateway_ref for the shared tr_... Transfer ID. payment_gateway_ref, refund_gateway_ref and payout_gateway_ref retain their existing values. Previously recorded released transfers are copied into the new field without modifying the original field. The existing per-deal DB lock serializes releases. Stripe requests use idempotency key release-deal-<dealId> and transfer_group deal_<dealId>. Metadata contains dealId, hostUserId, originalAmount/originalCurrency, paymentCount and a SHA-256 fingerprint of the complete sorted payment/source/settlement set.

On retry the backend searches the deal transfer group and validates its recipient, total, currency and payment fingerprint before recording the existing Transfer ID. If only some DB rows were saved, it includes all RELEASED and pending rows in the same snapshot and only looks up the existing transfer; it cannot create a new one. If no rows were saved before a crash, the same group lookup still recovers the provider transfer, including after the provider's idempotency retention period. Fully RELEASED deals do not create another transfer.

The backend rejects changed payment membership, mixed currencies, multiple previous deal transfers, and old single-payment transfers in payment_<paymentId> groups. Previously paid deals require reconciliation instead of being paid again. Refunds/new payments are blocked once payout has started in the current source service.

LegacyAggregateReleaseConfiguration redirects the deployed checkout snapshot's nested-record ReleaseFunds method to the same aggregate service, preserving its existing checkout/webhook runtime and DB configuration. Fresh builds already delegate directly and do not need that compatibility proxy.

### Retrying after funding the sandbox

Stripe can replay a cached balance_insufficient error for the original key even after balance is funded. The gateway advances a bounded stable key sequence (-funded-1, -funded-2, -funded-3) only after an explicit insufficient-funds response, after checking the transfer group again and confirming available funds cover the total. Unknown errors/timeouts never rotate keys. All attempts retain the same deal group and payment fingerprint; normal releases remain serialized by the deal DB lock. No retry is performed if balance is still insufficient.
