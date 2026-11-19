create table saas_inventory_snapshot (
    id bigint generated always as identity primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    installation_id uuid not null references saas_installation(id),
    snapshot_id uuid not null,
    captured_at timestamptz not null,
    chunk_count integer not null check (chunk_count > 0),
    product_count integer not null check (product_count >= 0),
    warehouse_count integer not null check (warehouse_count >= 0),
    balance_count integer not null check (balance_count >= 0),
    completed_at timestamptz,
    active_product_count integer,
    positive_count integer,
    zero_count integer,
    negative_count integer,
    unique (company_id, store_id, snapshot_id)
);
create index ix_inventory_current on saas_inventory_snapshot(company_id, store_id, installation_id, captured_at desc, snapshot_id desc)
    where completed_at is not null;
create index ix_inventory_history on saas_inventory_snapshot(company_id, store_id, captured_at desc, snapshot_id desc)
    where completed_at is not null;

create table saas_inventory_chunk (
    snapshot bigint not null references saas_inventory_snapshot(id) on delete cascade,
    chunk_index integer not null check (chunk_index >= 0),
    payload_hash varchar(64) not null,
    primary key (snapshot, chunk_index)
);
create table saas_inventory_product (
    snapshot bigint not null references saas_inventory_snapshot(id) on delete cascade,
    product_id uuid not null,
    code varchar(128),
    name varchar(255) not null,
    barcode varchar(128),
    secondary_barcode varchar(128),
    active boolean not null,
    product_type varchar(7) not null check (product_type in ('UNIT','WEIGHT','SERVICE')),
    family_id uuid,
    family_name varchar(128),
    subfamily_id uuid,
    subfamily_name varchar(128),
    primary key (snapshot, product_id)
);
create table saas_inventory_warehouse (
    snapshot bigint not null references saas_inventory_snapshot(id) on delete cascade,
    warehouse_id uuid not null,
    name varchar(128) not null,
    active boolean not null,
    default_warehouse boolean not null,
    primary key (snapshot, warehouse_id)
);
-- Fragments can arrive before the dimensions they reference. The projector checks
-- both references, in this same snapshot, before setting completed_at.
create table saas_inventory_balance (
    snapshot bigint not null references saas_inventory_snapshot(id) on delete cascade,
    product_id uuid not null,
    warehouse_id uuid not null,
    quantity numeric(19,3) not null,
    primary key (snapshot, product_id, warehouse_id)
);
create index ix_inventory_balance_warehouse on saas_inventory_balance(snapshot, warehouse_id, product_id);
