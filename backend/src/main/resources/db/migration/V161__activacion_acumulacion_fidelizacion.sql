ALTER TABLE member_settings
    ADD COLUMN points_accrual_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN points_accrual_base_amount NUMERIC(19, 2) NOT NULL DEFAULT 1.00,
    ADD COLUMN balance_accrual_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN balance_accrual_base_amount NUMERIC(19, 2) NOT NULL DEFAULT 1.00;

UPDATE member_settings
SET balance_accrual_enabled = balance_accrual_percent > 0;
