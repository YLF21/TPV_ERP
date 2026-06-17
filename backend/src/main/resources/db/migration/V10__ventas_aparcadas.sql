create table venta_aparcada (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    creado_por uuid not null references usuario(id),
    creado_en timestamptz not null,
    cliente_id uuid references cliente(id),
    comentario varchar(500),
    total numeric(19,2) not null,
    documento jsonb not null
);

create index idx_venta_aparcada_tienda_fecha
    on venta_aparcada(tienda_id, creado_en desc);
