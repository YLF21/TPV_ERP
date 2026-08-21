ALTER TABLE member_balance_reservation_local
    ADD COLUMN reserved_loyalty_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN reserved_return_credit_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN prepared_loyalty_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN prepared_return_credit_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN consumed_loyalty_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN consumed_return_credit_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN account_loyalty_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN account_return_credit_balance NUMERIC(19, 2) NOT NULL DEFAULT 0;

UPDATE member_balance_reservation_local
SET reserved_loyalty_amount = reserved_total,
    prepared_loyalty_amount = prepared_amount,
    consumed_loyalty_amount = consumed_total,
    account_loyalty_balance = account_balance;

ALTER TABLE member_balance_reservation_local
    ADD CONSTRAINT ck_member_balance_reservation_local_typed_amounts
        CHECK (
            reserved_loyalty_amount >= 0
            AND reserved_return_credit_amount >= 0
            AND prepared_loyalty_amount >= 0
            AND prepared_return_credit_amount >= 0
            AND consumed_loyalty_amount >= 0
            AND consumed_return_credit_amount >= 0
            AND account_loyalty_balance >= 0
            AND account_return_credit_balance >= 0
            AND prepared_loyalty_amount <= reserved_loyalty_amount
            AND prepared_return_credit_amount <= reserved_return_credit_amount
            AND consumed_loyalty_amount <= reserved_loyalty_amount
            AND consumed_return_credit_amount <= reserved_return_credit_amount
            AND reserved_total = reserved_loyalty_amount + reserved_return_credit_amount
            AND prepared_amount = prepared_loyalty_amount + prepared_return_credit_amount
            AND consumed_total = consumed_loyalty_amount + consumed_return_credit_amount
            AND account_balance = account_loyalty_balance
        );
