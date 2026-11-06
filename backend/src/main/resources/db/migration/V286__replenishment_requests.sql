-- Informational only: no triggers or writes to stock/movements.
create table replenishment_request (
    id uuid primary key,
    store_id uuid not null references tienda(id),
    product_id uuid not null references producto(id),
    warehouse_id uuid not null references almacen(id),
    quantity numeric(19,3) not null check (quantity > 0),
    reason varchar(500) not null check (length(trim(reason)) > 0),
    status varchar(16) not null default 'PENDING' check (status in ('PENDING', 'ORDERED')),
    order_date date,
    created_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    constraint ck_replenishment_order_date check (
        (status = 'PENDING' and order_date is null) or (status = 'ORDERED' and order_date is not null))
);
create index ix_replenishment_store_warehouse on replenishment_request(store_id, warehouse_id, created_at, id);
create index ix_replenishment_expiration on replenishment_request(store_id, order_date) where status = 'ORDERED';
create index ix_replenishment_product on replenishment_request(product_id);
