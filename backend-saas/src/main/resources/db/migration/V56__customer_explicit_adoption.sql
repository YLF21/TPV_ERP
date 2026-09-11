-- Explicit adoption adds verified copies; it never merges customers by a tax number.
alter table saas_customer_identity_link
    drop constraint saas_customer_identity_link_customer_id_key,
    add constraint uq_saas_customer_link_installation_customer unique (installation_id, customer_id);

create function saas_guard_customer_identity_link_owner() returns trigger
language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'CUSTOMER_IDENTITY_CONFLICT' using errcode = '23514';
    end if;
    if row(new.installation_id, new.local_customer_id, new.company_id, new.customer_id)
            is distinct from row(old.installation_id, old.local_customer_id, old.company_id, old.customer_id) then
        raise exception 'CUSTOMER_IDENTITY_CONFLICT' using errcode = '23514';
    end if;
    return new;
end;
$$;
create trigger trg_saas_customer_identity_link_owner
before update or delete on saas_customer_identity_link
for each row execute function saas_guard_customer_identity_link_owner();

-- RESERVED contains the selected profile snapshot. Only a committed local outbox creates the link.
create table saas_customer_adoption_operation (
    operation_id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    installation_id uuid not null references saas_installation(id),
    local_customer_id uuid not null,
    customer_id uuid references saas_erp_customer(id),
    expected_revision bigint check (expected_revision >= 0),
    document_type varchar(20),
    document_number varchar(64),
    profile_json jsonb,
    status varchar(16) not null check (status in ('RESERVED', 'COMMITTED', 'CANCELLED')),
    created_at timestamptz not null default now(),
    committed_at timestamptz,
    constraint ck_saas_customer_adoption_reserved check (status = 'CANCELLED' or
        (customer_id is not null and expected_revision is not null and document_type is not null
         and document_number is not null and profile_json is not null))
);
create unique index uq_saas_customer_adoption_pending_local
    on saas_customer_adoption_operation(installation_id, local_customer_id) where status = 'RESERVED';
create unique index uq_saas_customer_adoption_pending_central
    on saas_customer_adoption_operation(installation_id, customer_id) where status = 'RESERVED';
