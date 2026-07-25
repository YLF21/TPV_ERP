ALTER TABLE sale_payment_allocation
    ADD COLUMN delivered numeric(19,2),
    ADD COLUMN change numeric(19,2),
    ADD COLUMN comment varchar(512);

ALTER TABLE documento_pago
    ADD COLUMN comentario varchar(512);

UPDATE sale_payment_allocation
SET delivered = amount,
    change = 0
WHERE kind = 'CASH';

ALTER TABLE sale_payment_allocation
    ADD CONSTRAINT ck_sale_payment_allocation_cash_amounts
    CHECK (
        (kind = 'CASH' AND delivered IS NOT NULL AND change IS NOT NULL
            AND delivered >= amount AND change = delivered - amount)
        OR
        (kind <> 'CASH' AND delivered IS NULL AND change IS NULL)
    );
