-- Optional v2 metadata is unknown for previously projected documents; no historical inference.
alter table saas_commercial_document
    add column cancelled_by_local_id uuid,
    add column source_cancelled_at timestamp with time zone,
    add column due_date date,
    add column settled_by_origin boolean,
    add column relationships_complete boolean not null default false;

create table saas_commercial_document_relation (
    company_id uuid not null,
    store_id uuid not null,
    source_document_id uuid not null,
    relation_type varchar(16) not null,
    origin_document_id uuid not null,
    primary key (company_id, store_id, source_document_id, relation_type, origin_document_id),
    constraint fk_saas_commercial_document_relation_owner
        foreign key (company_id, store_id, source_document_id)
        references saas_commercial_document(company_id, store_id, source_document_id),
    constraint ck_saas_commercial_document_relation_type
        check (relation_type in ('FACTURA_DE', 'RECTIFICA', 'COMPENSA')),
    constraint ck_saas_commercial_document_relation_not_self
        check (source_document_id <> origin_document_id)
);

-- The origin can arrive later; its UUID is scoped to the owner's company and store, without an origin FK.
create index ix_saas_commercial_document_relation_origin
    on saas_commercial_document_relation(company_id, store_id, origin_document_id,
        relation_type, source_document_id);
