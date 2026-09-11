-- Read projection of explicitly versioned DOCUMENTO v2 snapshots received by SaaS.
-- Local customer/actor/terminal UUIDs are not central master identities.
create table saas_commercial_document (
    company_id uuid not null references saas_company(id),
    store_id uuid not null,
    source_document_id uuid not null,
    source_installation_id uuid not null references saas_installation(id),
    source_event_id uuid not null references saas_sync_event(event_id),
    source_payload_hash varchar(64) not null,
    source_revision bigint not null,
    schema_version integer not null,
    document_type varchar(24) not null,
    document_status varchar(16) not null,
    document_number varchar(32) not null,
    business_date date not null,
    currency varchar(3) not null,
    subtotal numeric(19,2) not null,
    tax_total numeric(19,2) not null,
    total numeric(19,2) not null,
    customer_local_id uuid,
    created_by_local_id uuid,
    confirmed_by_local_id uuid,
    source_created_at timestamp with time zone,
    source_confirmed_at timestamp with time zone,
    origin_terminal_local_id uuid,
    received_at timestamp with time zone not null,
    primary key (company_id, store_id, source_document_id),
    constraint fk_saas_commercial_document_store_company
        foreign key (store_id, company_id) references saas_store(id, company_id),
    constraint ck_saas_commercial_document_revision check (source_revision >= 0),
    constraint ck_saas_commercial_document_schema check (schema_version = 2),
    constraint ck_saas_commercial_document_hash check (source_payload_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_saas_commercial_document_type
        check (document_type in ('TICKET', 'ALBARAN_VENTA', 'FACTURA_VENTA', 'RECTIFICATIVA_VENTA')),
    constraint ck_saas_commercial_document_status
        check (document_status in ('CONFIRMADO', 'ANULADO', 'PENDIENTE', 'PARCIAL', 'PAGADO')),
    constraint ck_saas_commercial_document_number check (btrim(document_number) <> ''),
    constraint ck_saas_commercial_document_currency check (currency ~ '^[A-Z]{3}$')
);

create index ix_saas_commercial_document_company_date
    on saas_commercial_document(company_id, business_date desc, store_id, source_document_id);
create index ix_saas_commercial_document_store_date
    on saas_commercial_document(company_id, store_id, business_date desc, source_document_id);
create index ix_saas_commercial_document_customer_date
    on saas_commercial_document(company_id, source_installation_id, customer_local_id,
        business_date desc, source_document_id)
    where customer_local_id is not null;

-- Idempotency evidence for every received revision, including older snapshots.
-- Payload stays only in saas_sync_event; a revision may never acquire a second hash.
create table saas_commercial_document_revision (
    company_id uuid not null,
    store_id uuid not null,
    source_document_id uuid not null,
    source_revision bigint not null check (source_revision >= 0),
    source_payload_hash varchar(64) not null check (source_payload_hash ~ '^[0-9a-f]{64}$'),
    source_event_id uuid not null references saas_sync_event(event_id),
    primary key (company_id, store_id, source_document_id, source_revision),
    foreign key (company_id, store_id, source_document_id)
        references saas_commercial_document(company_id, store_id, source_document_id)
);
