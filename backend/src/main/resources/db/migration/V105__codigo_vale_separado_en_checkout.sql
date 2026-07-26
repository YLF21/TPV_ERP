ALTER TABLE sale_payment_allocation
    ADD COLUMN voucher_code varchar(128);

UPDATE sale_payment_allocation
SET voucher_code = reference,
    reference = NULL
WHERE kind = 'VOUCHER'
  AND reference IS NOT NULL;

CREATE INDEX idx_sale_payment_allocation_voucher_code
    ON sale_payment_allocation (upper(btrim(voucher_code)))
    WHERE voucher_code IS NOT NULL;
