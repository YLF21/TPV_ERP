-- Current informational request only. Deleted and expired rows retain a revision fence,
-- without product or commercial data, to reject delayed events.
create table saas_replenishment_request (
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    request_id uuid not null,
    revision bigint not null check (revision >= 0),
    payload_hash varchar(64) not null,
    state varchar(16) not null check (state in ('PENDING', 'ORDERED', 'DELETED', 'EXPIRED')),
    product_id uuid,
    warehouse_id uuid,
    quantity numeric(19,3),
    reason varchar(500),
    order_date date,
    created_at timestamp with time zone,
    expires_at timestamp with time zone,
    metadata_json jsonb,
    primary key (company_id, store_id, request_id),
    check ((state in ('DELETED', 'EXPIRED') and product_id is null and warehouse_id is null
            and quantity is null and reason is null and order_date is null
            and created_at is null and expires_at is null and metadata_json is null)
        or (state in ('PENDING', 'ORDERED') and product_id is not null and warehouse_id is not null
            and quantity is not null and quantity > 0 and reason is not null
            and length(btrim(reason)) > 0 and created_at is not null
            and metadata_json is not null and jsonb_typeof(metadata_json) = 'object'
            and ((state = 'PENDING' and order_date is null and expires_at is null)
                or (state = 'ORDERED' and order_date is not null and expires_at is not null))))
);
create index ix_saas_replenishment_expiry on saas_replenishment_request(expires_at)
    where state = 'ORDERED';
