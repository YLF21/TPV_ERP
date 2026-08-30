ALTER TABLE member_balance_reservation_local
    ADD COLUMN retention_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN retention_fingerprint VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN retention_attributed_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN retention_held_known NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN retention_pending_missing NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN retention_spent_shortfall NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN retention_spendable NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN retention_recovered_known NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN retention_reserved_lots JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE member_balance_reservation_local
    ADD CONSTRAINT ck_member_balance_reservation_local_retention
        CHECK (retention_revision >= 0
            AND retention_attributed_amount >= 0
            AND retention_held_known >= 0
            AND retention_pending_missing >= 0
            AND retention_spent_shortfall >= 0
            AND retention_spendable >= 0
            AND retention_recovered_known >= 0
            AND retention_held_known + retention_pending_missing
                + retention_spent_shortfall + retention_recovered_known
                = retention_attributed_amount);
