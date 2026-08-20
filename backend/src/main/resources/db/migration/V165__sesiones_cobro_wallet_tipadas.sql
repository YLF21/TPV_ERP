ALTER TABLE sale_payment_session
    ADD COLUMN member_return_credit_requested_amount NUMERIC(19, 2),
    ADD COLUMN member_return_credit_applied_amount NUMERIC(19, 2);

UPDATE sale_payment_session
SET member_return_credit_requested_amount = 0.00,
    member_return_credit_applied_amount = 0.00
WHERE member_return_credit_requested_amount IS NULL
   OR member_return_credit_applied_amount IS NULL;

ALTER TABLE sale_payment_session
    ALTER COLUMN member_return_credit_requested_amount SET NOT NULL,
    ALTER COLUMN member_return_credit_applied_amount SET NOT NULL,
    ADD CONSTRAINT ck_sale_payment_session_member_return_credit_amounts
        CHECK (
            member_return_credit_requested_amount >= 0
            AND member_return_credit_applied_amount >= 0
            AND member_return_credit_applied_amount
                <= member_return_credit_requested_amount
        );
