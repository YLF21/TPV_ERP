-- Forward-only hardening for delivery leases, fiscal evidence and active-license quotas.

alter table saas_billing_invoice
    add column fiscal_reason varchar(500),
    add column fiscal_legal_basis varchar(500),
    add column fiscal_evidence_reference varchar(500);

-- NOT VALID preserves historic rows for explicit review while PostgreSQL enforces
-- evidence for every new or updated decision. Payment code blocks undocumented legacy rows.
alter table saas_billing_invoice
    add constraint ck_saas_invoice_not_applicable_evidence check (
        (fiscal_status = 'NOT_APPLICABLE'
            and nullif(btrim(fiscal_reason), '') is not null
            and nullif(btrim(fiscal_legal_basis), '') is not null
            and nullif(btrim(fiscal_evidence_reference), '') is not null)
        or
        (fiscal_status <> 'NOT_APPLICABLE'
            and fiscal_reason is null
            and fiscal_legal_basis is null
            and fiscal_evidence_reference is null)
    ) not valid;

create table saas_invoice_fiscal_decision_audit (
    id uuid primary key,
    invoice_id uuid not null references saas_billing_invoice(id),
    previous_status varchar(32) not null,
    new_status varchar(32) not null,
    reason varchar(500),
    legal_basis varchar(500),
    evidence_reference varchar(500),
    changed_by varchar(80) not null,
    changed_at timestamp with time zone not null,
    constraint ck_saas_fiscal_audit_status
        check (new_status in ('CALCULATED', 'NOT_APPLICABLE')),
    constraint ck_saas_fiscal_audit_evidence check (
        new_status <> 'NOT_APPLICABLE'
        or (nullif(btrim(reason), '') is not null
            and nullif(btrim(legal_basis), '') is not null
            and nullif(btrim(evidence_reference), '') is not null)
    )
);

create index idx_saas_invoice_fiscal_audit
    on saas_invoice_fiscal_decision_audit(invoice_id, changed_at desc);

drop index if exists idx_saas_security_outbox_delivery;
create index idx_saas_security_outbox_delivery
    on saas_security_notification_outbox(status, next_attempt_at, claimed_at, created_at)
    where status in ('PENDING', 'PROCESSING');

drop index if exists idx_saas_integration_run_delivery;
create index idx_saas_integration_run_delivery
    on saas_integration_run(status, next_attempt_at, claimed_at, started_at)
    where status in ('PENDING', 'PROCESSING');

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
        select count(*) into used from saas_store
         where company_id = new.company_id
           and (tg_op <> 'UPDATE' or id <> new.id);
    elsif tg_table_name = 'saas_license' then
        limit_column := 'max_licenses';
        if new.status <> 'VALIDA' or new.valid_until <= current_timestamp then
            return new;
        end if;
        select count(*) into used from saas_license
         where company_id = new.company_id
           and status = 'VALIDA'
           and valid_until > current_timestamp
           and (tg_op <> 'UPDATE' or id <> new.id);
    else
        limit_column := 'max_tenant_users';
        if new.active = false then
            return new;
        end if;
        select count(*) into used from saas_tenant_user
         where company_id = new.company_id
           and active = true
           and (tg_op <> 'UPDATE' or id <> new.id);
    end if;
    allowed := saas_plan_limit(new.company_id, limit_column);
    if used >= allowed then
        raise exception 'Limite de plan alcanzado para %', tg_table_name;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_saas_license_plan_limit on saas_license;
create trigger trg_saas_license_plan_limit
before insert or update of status, valid_until, company_id on saas_license
for each row execute function enforce_saas_simple_plan_limit();
