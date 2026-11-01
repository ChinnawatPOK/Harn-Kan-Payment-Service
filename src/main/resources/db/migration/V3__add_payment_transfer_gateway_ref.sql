ALTER TABLE payments ADD COLUMN payment_transfer_gateway_ref VARCHAR(255) NULL AFTER payout_gateway_ref;

-- Copy previously recorded transfers to the new field; preserve every original reference unchanged.
UPDATE payments
SET payment_transfer_gateway_ref = payout_gateway_ref
WHERE payout_gateway_ref IS NOT NULL
  AND (status = 'RELEASED' OR (status = 'PAYOUT_PENDING' AND LEFT(payout_gateway_ref, 3) = 'tr_'));
