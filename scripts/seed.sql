-- Manual local Fake Gateway fixtures only. Existing rows are preserved.
INSERT IGNORE INTO payments (
    id, deal_id, participant_id, amount, status, reserved_until,
    is_confirmed_receipt, created_at, updated_at
) VALUES (
    '11111111111111111111111111111111',
    '22222222222222222222222222222222',
    '33333333333333333333333333333333',
    60.00, 'RESERVED', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 5 MINUTE),
    FALSE, UTC_TIMESTAMP(), UTC_TIMESTAMP()
);

INSERT IGNORE INTO payments (
    id, deal_id, participant_id, amount, payment_gateway_ref, status,
    is_confirmed_receipt, created_at, updated_at
) VALUES (
    '44444444444444444444444444444444',
    '55555555555555555555555555555555',
    '66666666666666666666666666666666',
    60.00, 'PAY-TEST-001', 'HELD', FALSE, UTC_TIMESTAMP(), UTC_TIMESTAMP()
);
