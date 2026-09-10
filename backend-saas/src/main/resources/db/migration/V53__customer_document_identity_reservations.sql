-- Reservations and committed masters share one namespace. A pending rename
-- holds BOTH identifiers until the local ERP's committed outbox is received.
create function saas_customer_document_key(value text) returns text
language sql immutable parallel safe as $$
    select upper(replace(replace(btrim(coalesce(value, ''),
        (select string_agg(chr(code), '' order by code) from generate_series(1, 32) as code)), ' ', ''), '-', ''));
$$;

-- Never merge customer identities or guess the type of a legacy document.
do $$
begin
    if exists (
        select 1 from saas_erp_customer
        where saas_customer_document_key(tax_id) <> ''
        group by company_id, saas_customer_document_key(tax_id) having count(*) > 1
    ) then
        raise exception 'CUSTOMER_DOCUMENT_DUPLICATE: reconcile legacy customer identities before migration';
    end if;
end;
$$;

alter table saas_erp_customer
    alter column tax_id type varchar(64),
    alter column name type varchar(255),
    alter column phone type varchar(64),
    alter column email type varchar(320),
    add column document_type varchar(20),
    add column identity_revision bigint not null default 0,
    add column address_json jsonb,
    add constraint ck_saas_customer_document_type
        check (document_type is null or document_type in ('NIE', 'DNI', 'NIF', 'PASAPORTE'));

create unique index uq_saas_customer_document_number
    on saas_erp_customer(company_id, saas_customer_document_key(tax_id))
    where saas_customer_document_key(tax_id) <> '';

create table saas_customer_document_claim (
    company_id uuid not null references saas_company(id),
    document_number varchar(64) not null,
    customer_id uuid not null,
    primary key(company_id, document_number),
    constraint ck_saas_customer_document_claim_key
        check (document_number <> '' and document_number = saas_customer_document_key(document_number))
);
insert into saas_customer_document_claim(company_id, document_number, customer_id)
select company_id, saas_customer_document_key(tax_id), id from saas_erp_customer
where saas_customer_document_key(tax_id) <> '';

create table saas_customer_code_claim (
    company_id uuid not null references saas_company(id),
    code varchar(40) not null,
    customer_id uuid not null,
    primary key(company_id, code)
);
insert into saas_customer_code_claim(company_id, code, customer_id)
select company_id, code, id from saas_erp_customer;

create table saas_customer_identity_link (
    installation_id uuid not null references saas_installation(id),
    local_customer_id uuid not null,
    company_id uuid not null references saas_company(id),
    customer_id uuid not null unique references saas_erp_customer(id),
    primary key(installation_id, local_customer_id)
);

create table saas_customer_identity_operation (
    operation_id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    installation_id uuid not null references saas_installation(id),
    local_customer_id uuid not null,
    customer_id uuid,
    expected_revision bigint,
    revision bigint,
    document_type varchar(20),
    document_number varchar(64),
    request_hash varchar(64),
    client_code varchar(40),
    prepared_profile_hash varchar(64),
    status varchar(16) not null,
    finalized_payload_hash varchar(64),
    created_at timestamp with time zone not null,
    committed_at timestamp with time zone,
    constraint ck_saas_customer_identity_operation_type
        check (document_type in ('NIE', 'DNI', 'NIF', 'PASAPORTE')),
    constraint ck_saas_customer_identity_operation_status
        check (status in ('RESERVED', 'COMMITTED', 'CANCELLED')),
    constraint ck_saas_customer_identity_operation_revision
        check (revision > 0 and (expected_revision is null or revision = expected_revision + 1)),
    constraint ck_saas_customer_identity_operation_reservation
        check (status = 'CANCELLED' or (customer_id is not null and revision is not null
            and document_type is not null and document_number is not null and request_hash is not null
            and client_code is not null and prepared_profile_hash is not null))
);
create unique index uq_saas_customer_pending_identity_operation
    on saas_customer_identity_operation(installation_id, local_customer_id) where status = 'RESERVED';
create index ix_saas_customer_identity_operation_owner
    on saas_customer_identity_operation(company_id, customer_id, status);

-- Pending creates reserve master capacity too. Finalization marks the operation
-- committed in the same transaction before inserting, consuming its held slot.
create function saas_customer_reserved_master_count(target_company uuid) returns bigint
language sql stable as $$
    select count(*) from saas_customer_identity_operation
    where company_id = target_company and status = 'RESERVED' and expected_revision is null;
$$;

create or replace function enforce_saas_master_plan_limit()
returns trigger language plpgsql as $$
declare used bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(new.company_id::text, 0));
    select (select count(*) from saas_erp_customer where company_id = new.company_id)
         + (select count(*) from saas_erp_product where company_id = new.company_id)
         + (select count(*) from saas_erp_supplier where company_id = new.company_id)
         + (select count(*) from saas_erp_warehouse where company_id = new.company_id)
         + saas_customer_reserved_master_count(new.company_id)
    into used;
    if used >= saas_plan_limit(new.company_id, 'max_master_records') then
        raise exception 'Limite de maestros del plan alcanzado';
    end if;
    return new;
end;
$$;

create function saas_guard_customer_document_identity() returns trigger
language plpgsql as $$
declare
    new_key text;
    old_key text;
    claim_owner uuid;
    material_change boolean;
begin
    -- Same lock order as reservation/finalization and existing master plan limit.
    perform pg_advisory_xact_lock(hashtextextended(coalesce(new.company_id, old.company_id)::text, 0));
    if tg_op = 'DELETE' then
        if exists (select 1 from saas_customer_identity_operation
                   where customer_id = old.id and status = 'RESERVED') then
            raise exception 'CUSTOMER_IDENTITY_CONFLICT' using errcode = '23514';
        end if;
        delete from saas_customer_document_claim where company_id = old.company_id and customer_id = old.id;
        delete from saas_customer_code_claim where company_id = old.company_id and customer_id = old.id;
        return old;
    end if;
    new_key := saas_customer_document_key(new.tax_id);
    if tg_op = 'UPDATE' then
        old_key := saas_customer_document_key(old.tax_id);
        if new.company_id <> old.company_id or new.id <> old.id then
            raise exception 'CUSTOMER_IDENTITY_CONFLICT' using errcode = '23514';
        end if;
        material_change := row(new.code, new.name, new.tax_id, new.document_type, new.phone, new.email, new.address_json, new.active)
                is distinct from row(old.code, old.name, old.tax_id, old.document_type, old.phone, old.email, old.address_json, old.active);
        if material_change and exists (select 1 from saas_customer_identity_operation
                            where customer_id = old.id and status = 'RESERVED') then
            raise exception 'CUSTOMER_IDENTITY_CONFLICT' using errcode = '23514';
        end if;
        if material_change then
            -- Finalization writes the whole prepared profile, so optimistic
            -- concurrency must include central CSV/profile edits, not just NIF.
            new.identity_revision := old.identity_revision + 1;
        else
            -- Explicit changes to revision are only made by a finalized operation.
            if new.identity_revision <> old.identity_revision and not exists (
                select 1 from saas_customer_identity_operation
                where customer_id = new.id and status = 'COMMITTED' and revision = new.identity_revision
                  and document_number = new_key and document_type = new.document_type
            ) then
                raise exception 'CUSTOMER_IDENTITY_CONFLICT' using errcode = '23514';
            end if;
        end if;
    elsif new_key = '' then
        raise exception 'CUSTOMER_DOCUMENT_INVALID' using errcode = '23514';
    end if;
    if new_key <> '' then
        insert into saas_customer_document_claim(company_id, document_number, customer_id)
        values (new.company_id, new_key, new.id) on conflict do nothing;
        select customer_id into claim_owner from saas_customer_document_claim
        where company_id = new.company_id and document_number = new_key;
        if claim_owner <> new.id then
            raise exception 'CUSTOMER_DOCUMENT_DUPLICATE' using errcode = '23505';
        end if;
    end if;
    insert into saas_customer_code_claim(company_id, code, customer_id)
    values (new.company_id, new.code, new.id) on conflict do nothing;
    select customer_id into claim_owner from saas_customer_code_claim
    where company_id = new.company_id and code = new.code;
    if claim_owner <> new.id then
        raise exception 'CUSTOMER_IDENTITY_CONFLICT' using errcode = '23505';
    end if;
    if tg_op = 'UPDATE' and old_key is distinct from new_key then
        delete from saas_customer_document_claim
        where company_id = old.company_id and customer_id = old.id and document_number = old_key;
    end if;
    if tg_op = 'UPDATE' and old.code is distinct from new.code then
        delete from saas_customer_code_claim
        where company_id = old.company_id and customer_id = old.id and code = old.code;
    end if;
    return new;
end;
$$;
create trigger trg_saas_customer_document_identity
before insert or update or delete on saas_erp_customer
for each row execute function saas_guard_customer_document_identity();
