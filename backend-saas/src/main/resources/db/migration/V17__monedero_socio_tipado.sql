ALTER TABLE saas_member_balance_account
    ADD COLUMN return_credit_balance NUMERIC(19, 2) NOT NULL DEFAULT 0;

ALTER TABLE saas_member_balance_account
    ADD CONSTRAINT ck_saas_member_return_credit_non_negative
        CHECK (return_credit_balance >= 0);

ALTER TABLE saas_member_balance_lot
    ADD COLUMN balance_type VARCHAR(20) NOT NULL DEFAULT 'LOYALTY';

ALTER TABLE saas_member_balance_lot
    ADD COLUMN document_id UUID;

ALTER TABLE saas_member_balance_lot
    ADD CONSTRAINT ck_saas_member_balance_lot_type
        CHECK (balance_type IN ('LOYALTY', 'RETURN_CREDIT'));

CREATE INDEX idx_saas_member_balance_lot_typed_fifo
    ON saas_member_balance_lot(account_id, balance_type, created_at, id);

ALTER TABLE saas_member_balance_reservation
    ADD COLUMN reserved_loyalty_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

ALTER TABLE saas_member_balance_reservation
    ADD COLUMN reserved_return_credit_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

ALTER TABLE saas_member_balance_reservation
    ADD COLUMN prepared_loyalty_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

ALTER TABLE saas_member_balance_reservation
    ADD COLUMN prepared_return_credit_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

ALTER TABLE saas_member_balance_reservation
    ADD COLUMN consumed_loyalty_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

ALTER TABLE saas_member_balance_reservation
    ADD COLUMN consumed_return_credit_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

UPDATE saas_member_balance_reservation
SET reserved_loyalty_amount = reserved_total,
    prepared_loyalty_amount = prepared_amount,
    consumed_loyalty_amount = consumed_total;

ALTER TABLE saas_member_balance_reservation
    DROP CONSTRAINT ck_saas_member_balance_reservation_amounts;

ALTER TABLE saas_member_balance_reservation
    DROP CONSTRAINT ck_saas_member_balance_reservation_prepare;

ALTER TABLE saas_member_balance_reservation
    ADD CONSTRAINT ck_saas_member_balance_reservation_legacy_loyalty
        CHECK (reserved_total = reserved_loyalty_amount
            AND prepared_amount = prepared_loyalty_amount
            AND consumed_total = consumed_loyalty_amount);

ALTER TABLE saas_member_balance_reservation
    ADD CONSTRAINT ck_saas_member_balance_reservation_typed_amounts
        CHECK (reserved_loyalty_amount >= 0
            AND reserved_return_credit_amount >= 0
            AND reserved_loyalty_amount + reserved_return_credit_amount > 0
            AND prepared_loyalty_amount >= 0
            AND prepared_loyalty_amount <= reserved_loyalty_amount
            AND prepared_return_credit_amount >= 0
            AND prepared_return_credit_amount <= reserved_return_credit_amount
            AND consumed_loyalty_amount >= 0
            AND consumed_loyalty_amount <= reserved_loyalty_amount
            AND consumed_return_credit_amount >= 0
            AND consumed_return_credit_amount <= reserved_return_credit_amount);

ALTER TABLE saas_member_balance_reservation
    ADD CONSTRAINT ck_saas_member_balance_reservation_typed_prepare
        CHECK ((status IN ('PREPARED', 'CONSUMED')
                AND prepare_operation_id IS NOT NULL
                AND prepared_loyalty_amount + prepared_return_credit_amount > 0
                AND prepared_at IS NOT NULL)
            OR status NOT IN ('PREPARED', 'CONSUMED'));

ALTER TABLE saas_member_balance_reservation_lot
    ADD COLUMN balance_type VARCHAR(20);

UPDATE saas_member_balance_reservation_lot reservation_lot
SET balance_type = lot.balance_type
FROM saas_member_balance_lot lot
WHERE lot.id = reservation_lot.lot_id;

ALTER TABLE saas_member_balance_reservation_lot
    ALTER COLUMN balance_type SET NOT NULL;

ALTER TABLE saas_member_balance_reservation_lot
    ALTER COLUMN balance_type SET DEFAULT 'LOYALTY';

ALTER TABLE saas_member_balance_reservation_lot
    ADD CONSTRAINT ck_saas_member_balance_reservation_lot_type
        CHECK (balance_type IN ('LOYALTY', 'RETURN_CREDIT'));

CREATE INDEX idx_saas_member_balance_reservation_lot_typed_fifo
    ON saas_member_balance_reservation_lot(reservation_id, balance_type, lot_id);
