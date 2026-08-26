ALTER TABLE sale_payment_session
    ADD COLUMN IF NOT EXISTS member_balance_recovery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS member_balance_recovery_last_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS member_balance_recovery_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS member_balance_recovery_last_error VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS member_balance_recovery_manual_review BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS member_balance_recovery_disposition VARCHAR(40) NOT NULL
        DEFAULT 'AUTOMATIC_RETRY';

ALTER TABLE sale_payment_session
    DROP CONSTRAINT IF EXISTS ck_sale_payment_session_member_recovery_attempts;

ALTER TABLE sale_payment_session
    ADD CONSTRAINT ck_sale_payment_session_member_recovery_attempts
    CHECK (member_balance_recovery_attempts >= 0);

ALTER TABLE sale_payment_session
    DROP CONSTRAINT IF EXISTS ck_sale_payment_session_member_recovery_disposition;

ALTER TABLE sale_payment_session
    ADD CONSTRAINT ck_sale_payment_session_member_recovery_disposition
    CHECK (member_balance_recovery_disposition IN (
        'AUTOMATIC_RETRY',
        'MANUAL_RECONCILIATION_REQUIRED'
    ));

CREATE INDEX IF NOT EXISTS ix_sale_payment_session_member_recovery
    ON sale_payment_session (
        member_balance_recovery_manual_review,
        member_balance_recovery_next_attempt_at,
        updated_at
    )
    WHERE member_balance_reservation_id IS NOT NULL
      AND member_balance_synchronized_at IS NULL;
