-- Central administrative masters legitimately start at revision zero. No historical revision is rewritten.
alter table cliente drop constraint ck_cliente_saas_identity;
alter table cliente add constraint ck_cliente_saas_identity
    check ((saas_customer_id is null and saas_identity_revision is null)
        or (saas_customer_id is not null and saas_identity_revision is not null and saas_identity_revision >= 0));
alter table cliente add column saas_client_code varchar(40);
alter table cliente add constraint ck_cliente_saas_client_code
    check (saas_client_code is null or (saas_customer_id is not null and length(btrim(saas_client_code)) > 0));

-- A durable local intent, not a customer or a central link. Rollback recovery cancels the reservation.
create table customer_adoption_operation (
    operation_id uuid primary key,
    company_id uuid not null references empresa(id),
    store_id uuid not null references tienda(id),
    customer_id uuid not null,
    central_customer_id uuid not null,
    expected_revision bigint not null check (expected_revision >= 0),
    document_type varchar(16) not null,
    document_number varchar(64) not null,
    state varchar(24) not null check (state in ('PENDING','CANCEL_PENDING','CANCELLED','LOCAL_COMMITTED')),
    created_at timestamptz not null default now()
);
create unique index ux_customer_adoption_pending_central
    on customer_adoption_operation(company_id, central_customer_id) where state in ('PENDING','CANCEL_PENDING');
create unique index ux_customer_adoption_pending_local
    on customer_adoption_operation(company_id, customer_id) where state in ('PENDING','CANCEL_PENDING');
create unique index ux_customer_adoption_pending_number
    on customer_adoption_operation(company_id, document_number) where state in ('PENDING','CANCEL_PENDING');
create index ix_customer_adoption_cancellation
    on customer_adoption_operation(created_at) where state = 'CANCEL_PENDING';
create index ix_customer_adoption_committed_owner
    on customer_adoption_operation(company_id, store_id, central_customer_id, created_at desc)
    where state = 'LOCAL_COMMITTED';
