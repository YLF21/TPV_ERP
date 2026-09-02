-- Effective plan quotas and provider-neutral operational foundations.

create table saas_plan_policy (
    plan_name varchar(80) primary key,
    max_tenant_users bigint not null check (max_tenant_users > 0),
    max_stores bigint not null check (max_stores > 0),
    max_licenses bigint not null check (max_licenses > 0),
    max_master_records bigint not null check (max_master_records > 0),
    max_sync_events_per_day bigint not null check (max_sync_events_per_day > 0)
);

insert into saas_plan_policy(plan_name, max_tenant_users, max_stores, max_licenses,
                             max_master_records, max_sync_events_per_day)
values ('BASIC', 3, 1, 1, 1000, 10000),
       ('STANDARD', 10, 3, 3, 10000, 100000),
       ('PREMIUM', 50, 20, 20, 100000, 1000000),
       ('ENTERPRISE', 500, 200, 200, 1000000, 10000000);

create or replace function saas_plan_limit(p_company_id uuid, p_column text)
returns bigint
language plpgsql
stable
as $$
declare
    result bigint;
begin
    execute format('select %I from saas_plan_policy where plan_name = coalesce('
        || '(select upper(plan_name) from saas_company_operations where company_id = $1), ''STANDARD'')',
        p_column)
    into result using p_company_id;
    if result is null then
        raise exception 'El plan de la empresa no tiene politica configurada';
    end if;
    return result;
end;
$$;

create or replace function enforce_saas_simple_plan_limit()
returns trigger
language plpgsql
as $$
declare
    used bigint;
    allowed bigint;
    limit_column text;
begin
    if tg_table_name = 'saas_store' then
        limit_column := 'max_stores';
    elsif tg_table_name = 'saas_license' then
        limit_column := 'max_licenses';
    else
        limit_column := 'max_tenant_users';
        if tg_op = 'UPDATE' and (old.active = true or new.active = false) then
            return new;
        end if;
    end if;
    execute format('select count(*) from %I where company_id = $1', tg_table_name)
    into used using new.company_id;
    allowed := saas_plan_limit(new.company_id, limit_column);
    if used >= allowed then
        raise exception 'Limite de plan alcanzado para %', tg_table_name;
    end if;
    return new;
end;
$$;

create trigger trg_saas_store_plan_limit before insert on saas_store
for each row execute function enforce_saas_simple_plan_limit();
create trigger trg_saas_license_plan_limit before insert on saas_license
for each row execute function enforce_saas_simple_plan_limit();
create trigger trg_saas_tenant_user_plan_limit before insert or update of active on saas_tenant_user
for each row execute function enforce_saas_simple_plan_limit();

create or replace function enforce_saas_master_plan_limit()
returns trigger
language plpgsql
as $$
declare
    used bigint;
begin
    select (select count(*) from saas_erp_customer where company_id = new.company_id)
         + (select count(*) from saas_erp_product where company_id = new.company_id)
         + (select count(*) from saas_erp_supplier where company_id = new.company_id)
         + (select count(*) from saas_erp_warehouse where company_id = new.company_id)
    into used;
    if used >= saas_plan_limit(new.company_id, 'max_master_records') then
        raise exception 'Limite de maestros del plan alcanzado';
    end if;
    return new;
end;
$$;

create trigger trg_saas_customer_plan_limit before insert on saas_erp_customer
for each row execute function enforce_saas_master_plan_limit();
create trigger trg_saas_product_plan_limit before insert on saas_erp_product
for each row execute function enforce_saas_master_plan_limit();
create trigger trg_saas_supplier_plan_limit before insert on saas_erp_supplier
for each row execute function enforce_saas_master_plan_limit();
create trigger trg_saas_warehouse_plan_limit before insert on saas_erp_warehouse
for each row execute function enforce_saas_master_plan_limit();

create or replace function enforce_saas_sync_daily_plan_limit()
returns trigger
language plpgsql
as $$
declare
    used bigint;
begin
    select count(*) into used from saas_sync_event
     where company_id = new.company_id
       and received_at >= new.received_at - interval '24 hours';
    if used >= saas_plan_limit(new.company_id, 'max_sync_events_per_day') then
        raise exception 'Limite diario de sincronizacion del plan alcanzado';
    end if;
    return new;
end;
$$;

create trigger trg_saas_sync_daily_plan_limit before insert on saas_sync_event
for each row execute function enforce_saas_sync_daily_plan_limit();

alter table saas_billing_invoice
    add column series varchar(24),
    add column fiscal_year integer,
    add column tax_regime varchar(16),
    add column tax_base varchar(32),
    add column tax_rate varchar(16),
    add column tax_amount varchar(32);

update saas_billing_invoice i
set series = coalesce(nullif(split_part(i.number, '-', 1), ''), 'GENERAL'),
    fiscal_year = extract(year from i.issued_at)::integer,
    tax_regime = c.tax_regime,
    tax_base = i.amount,
    tax_rate = '0.00',
    tax_amount = '0.00'
from saas_company c
where c.id = i.company_id;

alter table saas_billing_invoice
    alter column series set not null,
    alter column fiscal_year set not null,
    alter column tax_regime set not null,
    alter column tax_base set not null,
    alter column tax_rate set not null,
    alter column tax_amount set not null,
    add constraint ck_saas_invoice_fiscal_year check (fiscal_year between 2000 and 2200),
    add constraint ck_saas_invoice_tax_regime check (tax_regime in ('IVA', 'IGIC'));

create unique index uq_saas_invoice_series_year_number
    on saas_billing_invoice(company_id, fiscal_year, series, number);

create table saas_payment_reconciliation (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    payment_id uuid references saas_billing_payment(id),
    provider varchar(32) not null,
    external_reference varchar(160) not null,
    amount varchar(32) not null,
    currency varchar(8) not null,
    booked_at timestamp with time zone not null,
    status varchar(24) not null,
    notes varchar(500),
    created_at timestamp with time zone not null,
    constraint uq_saas_reconciliation_provider_reference unique(company_id, provider, external_reference),
    constraint ck_saas_reconciliation_provider check(provider in ('MANUAL_BANK', 'MANUAL_GATEWAY')),
    constraint ck_saas_reconciliation_status check(status in ('PENDING', 'MATCHED', 'REJECTED'))
);

create index idx_saas_reconciliation_company_status
    on saas_payment_reconciliation(company_id, status, booked_at desc);

create table saas_sync_outbox (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid references saas_store(id),
    entity_type varchar(80) not null,
    entity_id uuid not null,
    operation varchar(32) not null,
    payload text not null,
    status varchar(24) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp with time zone,
    last_error varchar(500),
    created_at timestamp with time zone not null,
    delivered_at timestamp with time zone,
    constraint ck_saas_sync_outbox_status check(status in ('PENDING', 'DELIVERED', 'FAILED')),
    constraint ck_saas_sync_outbox_attempts check(attempts >= 0)
);

create index idx_saas_sync_outbox_pending
    on saas_sync_outbox(status, next_attempt_at, created_at);
