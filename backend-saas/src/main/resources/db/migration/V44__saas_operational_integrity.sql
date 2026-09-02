-- Operational integrity for billing, company/store ownership and local integration runs.

alter table saas_store
    add constraint uq_saas_store_id_company unique (id, company_id);

alter table saas_sales_document
    add constraint fk_saas_sales_document_store_company
    foreign key (store_id, company_id) references saas_store(id, company_id);

create unique index uq_saas_billing_payment_invoice_reference
    on saas_billing_payment(invoice_id, reference)
    where reference is not null and trim(reference) <> '';

alter table saas_billing_invoice
    add constraint ck_saas_billing_invoice_amount_positive
    check (amount ~ '^[0-9]+([.][0-9]{1,2})?$' and cast(amount as numeric(19,2)) > 0),
    add constraint ck_saas_billing_invoice_status
    check (status in ('PENDIENTE', 'PARCIAL', 'PAGADA', 'VENCIDA'));

alter table saas_billing_payment
    add constraint ck_saas_billing_payment_amount_positive
    check (amount ~ '^[0-9]+([.][0-9]{1,2})?$' and cast(amount as numeric(19,2)) > 0);

create or replace function prevent_saas_billing_overpayment()
returns trigger
language plpgsql
as $$
declare
    invoice_total numeric(19,2);
    paid_total numeric(19,2);
begin
    select cast(amount as numeric(19,2))
      into invoice_total
      from saas_billing_invoice
     where id = new.invoice_id
     for update;

    if invoice_total is null then
        raise exception 'Factura no existe';
    end if;

    select coalesce(sum(cast(amount as numeric(19,2))), 0)
      into paid_total
      from saas_billing_payment
     where invoice_id = new.invoice_id
       and (tg_op <> 'UPDATE' or id <> new.id);

    if paid_total + cast(new.amount as numeric(19,2)) > invoice_total then
        raise exception 'El pago supera el saldo pendiente de la factura';
    end if;
    return new;
end;
$$;

drop trigger if exists trg_prevent_saas_billing_overpayment on saas_billing_payment;
create trigger trg_prevent_saas_billing_overpayment
before insert or update of amount, invoice_id on saas_billing_payment
for each row execute function prevent_saas_billing_overpayment();

create table saas_integration_run (
    id uuid primary key,
    integration_id uuid not null references saas_integration_endpoint(id),
    idempotency_key varchar(120) not null,
    attempt integer not null,
    status varchar(24) not null,
    delivery_mode varchar(32) not null,
    payload text,
    error_code varchar(80),
    error_message varchar(500),
    started_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    constraint uq_saas_integration_run_attempt unique(integration_id, idempotency_key, attempt),
    constraint ck_saas_integration_run_attempt check(attempt > 0),
    constraint ck_saas_integration_run_status check(status in ('RUNNING', 'SUCCEEDED', 'FAILED')),
    constraint ck_saas_integration_delivery_mode check(delivery_mode in ('LOCAL_OUTBOX'))
);

create index idx_saas_integration_run_history
    on saas_integration_run(integration_id, started_at desc);
