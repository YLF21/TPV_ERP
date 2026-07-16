alter table documento_pago add column request_id uuid;
alter table documento_pago
    add constraint uk_documento_pago_request unique (request_id);

create table customer_pending_sale_checkout (
    id uuid primary key,
    checkout_id uuid not null,
    terminal_id uuid not null references terminal(id),
    store_id uuid not null references tienda(id),
    user_id uuid not null references usuario(id),
    request_hash varchar(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    document_id uuid unique references documento(id),
    created_at timestamptz not null,
    completed_at timestamptz,
    version bigint not null default 0,
    unique (terminal_id, checkout_id),
    foreign key (terminal_id, store_id) references terminal(id, tienda_id)
);

create index idx_customer_receivable_scope
    on documento(tienda_id, cliente_id, estado, fecha_vencimiento)
    where tipo in ('ALBARAN_VENTA','FACTURA_VENTA')
      and estado in ('PENDIENTE','PARCIAL');
