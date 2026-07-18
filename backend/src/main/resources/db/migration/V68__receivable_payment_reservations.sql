alter table customer_pending_sale_checkout
    add column processing_owner uuid,
    add column processing_lease_until timestamptz;

update customer_pending_sale_checkout
set processing_owner = id,
    processing_lease_until = created_at
where processing_owner is null;

alter table customer_pending_sale_checkout
    alter column processing_owner set not null,
    alter column processing_lease_until set not null;

create table customer_receivable_payment_reservation (
    id uuid primary key,
    document_id uuid not null references documento(id),
    store_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    user_id uuid not null references usuario(id),
    request_hash varchar(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    amount numeric(19,2) not null check (amount > 0),
    kind varchar(24) not null check (kind in ('STANDARD','INTEGRATED_CARD')),
    status varchar(24) not null check (status in ('RESERVED','DISPATCHING','APPROVED','COMPLETED','RELEASED')),
    lease_owner uuid,
    lease_until timestamptz,
    document_payment_id uuid unique references documento_pago(id),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    version bigint not null default 0,
    foreign key (terminal_id, store_id) references terminal(id, tienda_id)
);

create index idx_customer_receivable_payment_reservation_active
    on customer_receivable_payment_reservation(document_id, status)
    where status in ('RESERVED','DISPATCHING','APPROVED');
