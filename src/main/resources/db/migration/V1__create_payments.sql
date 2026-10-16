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
