-- Production hardening for password lifecycle, quotas, reconciliation and fiscal metadata.

insert into saas_plan_policy(plan_name, max_tenant_users, max_stores, max_licenses,
                             max_master_records, max_sync_events_per_day)
select 'PRO', max_tenant_users, max_stores, max_licenses, max_master_records, max_sync_events_per_day
from saas_plan_policy where plan_name = 'PREMIUM'
on conflict (plan_name) do nothing;

update saas_company_operations set plan_name = upper(btrim(plan_name));
do $$
declare unknown_plans text;
begin
    select string_agg(distinct o.plan_name, ', ' order by o.plan_name)
      into unknown_plans
      from saas_company_operations o
      left join saas_plan_policy p on p.plan_name = o.plan_name
     where p.plan_name is null;
    if unknown_plans is not null then
        raise exception 'Planes SaaS sin politica configurada: %', unknown_plans;
    end if;
end;
$$;

alter table saas_company_operations
    add constraint fk_saas_company_operations_plan
    foreign key (plan_name) references saas_plan_policy(plan_name);

create or replace function enforce_saas_simple_plan_limit()
returns trigger
language plpgsql
as $$
declare
    used bigint;
    allowed bigint;
    limit_column text;
begin
    perform pg_advisory_xact_lock(hashtextextended(new.company_id::text, 0));
    if tg_table_name = 'saas_store' then
        limit_column := 'max_stores';
        select count(*) into used from saas_store where company_id = new.company_id;
    elsif tg_table_name = 'saas_license' then
        limit_column := 'max_licenses';
        select count(*) into used from saas_license where company_id = new.company_id;
    else
        limit_column := 'max_tenant_users';
        if new.active = false then
            return new;
        end if;
        if tg_op = 'UPDATE' then
            if old.active = true then
                return new;
            end if;
        end if;
        select count(*) into used from saas_tenant_user
        where company_id = new.company_id and active = true;
    end if;
    allowed := saas_plan_limit(new.company_id, limit_column);
    if used >= allowed then
        raise exception 'Limite de plan alcanzado para %', tg_table_name;
    end if;
    return new;
end;
$$;

create or replace function enforce_saas_master_plan_limit()
returns trigger
language plpgsql
as $$
declare used bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(new.company_id::text, 0));
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

create or replace function enforce_saas_sync_daily_plan_limit()
returns trigger
language plpgsql
as $$
declare used bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(new.company_id::text, 0));
    select count(*) into used from saas_sync_event
     where company_id = new.company_id
       and received_at >= new.received_at - interval '24 hours';
    if used >= saas_plan_limit(new.company_id, 'max_sync_events_per_day') then
        raise exception 'Limite diario de sincronizacion del plan alcanzado';
    end if;
    return new;
end;
$$;

do $$
declare duplicate_payments text;
begin
    select string_agg(payment_id::text, ', ' order by payment_id::text)
      into duplicate_payments
      from (
          select payment_id
            from saas_payment_reconciliation
           where payment_id is not null and status = 'MATCHED'
           group by payment_id
          having count(*) > 1
      ) duplicates;
    if duplicate_payments is not null then
        raise exception 'Pagos con conciliaciones MATCHED duplicadas; resolver antes de migrar: %', duplicate_payments;
    end if;
end;
$$;

create unique index uq_saas_reconciliation_matched_payment
    on saas_payment_reconciliation(payment_id)
    where payment_id is not null and status = 'MATCHED';

create or replace function prevent_duplicate_saas_password_reset()
returns trigger language plpgsql as $$
begin
    if new.consumed_at is not null then return new; end if;
    perform pg_advisory_xact_lock(hashtextextended(lower(new.username_key), 1));
    if exists(select 1 from saas_password_reset_token
              where realm = new.realm and lower(username_key) = lower(new.username_key)
                and consumed_at is null and id <> new.id) then
        raise exception 'Ya existe un reset activo para el usuario';
    end if;
    return new;
end;
$$;
create trigger trg_unique_saas_password_reset
before insert or update of realm, username_key, consumed_at on saas_password_reset_token
for each row execute function prevent_duplicate_saas_password_reset();

alter table saas_security_notification_outbox
    add column next_attempt_at timestamp with time zone,
    add constraint ck_saas_security_notification_status
        check (status in ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')),
    add constraint ck_saas_security_notification_attempts check (attempt_count >= 0);

alter table saas_billing_invoice
    alter column tax_base drop not null,
    alter column tax_rate drop not null,
    alter column tax_amount drop not null,
    add column fiscal_status varchar(32) not null default 'PENDING_TAX_DATA',
    add constraint ck_saas_invoice_fiscal_status
        check (fiscal_status in ('PENDING_TAX_DATA', 'CALCULATED', 'NOT_APPLICABLE'));

update saas_billing_invoice
set tax_base = null, tax_rate = null, tax_amount = null, fiscal_status = 'PENDING_TAX_DATA'
where tax_rate = '0.00' and tax_amount = '0.00';
alter table saas_integration_run
    drop constraint ck_saas_integration_run_status,
    add column next_attempt_at timestamp with time zone,
    add constraint ck_saas_integration_run_status
        check(status in ('RUNNING', 'PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'));

create index idx_saas_integration_run_pending
    on saas_integration_run(status, next_attempt_at, started_at);
