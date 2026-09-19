-- Read model of the latest received document snapshot. Numeric values are copied, never rounded.
alter table saas_commercial_document
    add column warehouse_local_id uuid,
    add column line_projection_status varchar(16) not null default 'PENDING',
    add constraint ck_saas_commercial_document_line_projection_status
        check (line_projection_status in ('PENDING', 'READY', 'MISSING', 'INVALID'));

create table saas_commercial_document_line (
    company_id uuid not null,
    store_id uuid not null,
    source_document_id uuid not null,
    line_position integer not null check (line_position > 0),
    product_local_id uuid,
    line_type varchar(40) not null,
    product_code text,
    product_name text,
    price_tariff text,
    quantity numeric not null,
    unit_price numeric not null,
    discount_percent numeric not null,
    line_total numeric not null,
    primary key (company_id, store_id, source_document_id, line_position),
    foreign key (company_id, store_id, source_document_id)
        references saas_commercial_document(company_id, store_id, source_document_id)
);

-- Codes are exact historical strings: neither numeric conversion nor case/space normalization.
create index ix_saas_commercial_document_line_product
    on saas_commercial_document_line(company_id, product_code, store_id, source_document_id, line_position)
    where product_code is not null;

-- The bounded worker reads only current snapshots that have not yet been examined.
create index ix_saas_commercial_document_lines_pending
    on saas_commercial_document(company_id, store_id, source_document_id)
    where line_projection_status = 'PENDING';
