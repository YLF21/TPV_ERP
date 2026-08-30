ALTER TABLE saas_member_balance_reservation
    DROP CONSTRAINT ck_saas_member_balance_reservation_typed_amounts;

ALTER TABLE saas_member_balance_reservation
    ADD CONSTRAINT ck_saas_member_balance_reservation_typed_amounts
        CHECK (reserved_loyalty_amount >= 0
            AND reserved_return_credit_amount >= 0
            AND (status IN ('ACTIVE', 'RELEASED', 'EXPIRED')
                OR reserved_loyalty_amount + reserved_return_credit_amount > 0)
            AND prepared_loyalty_amount >= 0
            AND prepared_loyalty_amount <= reserved_loyalty_amount
            AND prepared_return_credit_amount >= 0
            AND prepared_return_credit_amount <= reserved_return_credit_amount
            AND consumed_loyalty_amount >= 0
            AND consumed_loyalty_amount <= reserved_loyalty_amount
            AND consumed_return_credit_amount >= 0
            AND consumed_return_credit_amount <= reserved_return_credit_amount);
