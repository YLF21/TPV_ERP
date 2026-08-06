ALTER TABLE sale_payment_session
    DROP CONSTRAINT IF EXISTS sale_payment_session_total_check;

ALTER TABLE sale_payment_session
    ADD CONSTRAINT ck_sale_payment_session_total_non_negative CHECK (total >= 0),
    ADD COLUMN direction varchar(16) NOT NULL DEFAULT 'SALE';

ALTER TABLE sale_payment_allocation
    ADD COLUMN original_payment_id uuid REFERENCES documento_pago(id);

CREATE INDEX idx_sale_payment_allocation_original_payment
    ON sale_payment_allocation (original_payment_id)
    WHERE original_payment_id IS NOT NULL;
