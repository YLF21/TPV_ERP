ALTER TABLE sale_payment_session
    ADD COLUMN member_balance_reservation_id UUID,
    ADD COLUMN member_balance_requested_amount NUMERIC(19, 2),
    ADD COLUMN member_balance_applied_amount NUMERIC(19, 2),
    ADD COLUMN member_balance_failure_code VARCHAR(64),
    ADD COLUMN member_balance_synchronized_at TIMESTAMPTZ;

ALTER TABLE sale_payment_session
    ADD CONSTRAINT ck_sale_payment_session_member_balance_amounts
        CHECK (
            (member_balance_requested_amount IS NULL OR member_balance_requested_amount >= 0)
            AND (member_balance_applied_amount IS NULL OR member_balance_applied_amount >= 0)
            AND (
                member_balance_requested_amount IS NULL
                OR member_balance_applied_amount IS NULL
                OR member_balance_applied_amount <= member_balance_requested_amount
            )
        );

CREATE UNIQUE INDEX ux_sale_payment_session_member_balance_reservation
    ON sale_payment_session (member_balance_reservation_id)
    WHERE member_balance_reservation_id IS NOT NULL;

CREATE INDEX ix_sale_payment_session_member_balance_pending
    ON sale_payment_session (updated_at)
    WHERE member_balance_reservation_id IS NOT NULL
      AND member_balance_synchronized_at IS NULL;
