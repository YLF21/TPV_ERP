create table if not exists saas_member_balance_retention_receipt_alias (
    operation_id uuid primary key,
    receipt_operation_id uuid not null
        references saas_member_balance_retention_receipt(operation_id),
    created_at timestamp with time zone not null,
    version bigint not null default 0
);

create index if not exists ix_saas_member_balance_retention_receipt_alias_receipt
    on saas_member_balance_retention_receipt_alias(receipt_operation_id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM saas_member_balance_retention_receipt_alias alias_row
          JOIN saas_member_balance_retention_receipt receipt
            ON receipt.operation_id = alias_row.operation_id
    ) THEN
        RAISE EXCEPTION
            'No se puede aplicar V41: operation_id existe simultaneamente como receipt y alias; requiere reconciliacion explicita'
            USING ERRCODE = '23505';
    END IF;
END
$$;
