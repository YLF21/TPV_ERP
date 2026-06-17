create table vale (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    codigo varchar(32) not null,
    importe_inicial numeric(19,2) not null check (importe_inicial > 0),
    saldo numeric(19,2) not null check (saldo >= 0),
    status varchar(16) not null check (status in ('ACTIVE', 'CONSUMED')),
    creado_en timestamptz not null,
    tickets_origen jsonb not null,
    version bigint not null default 0,
    constraint uk_vale_tienda_codigo unique (tienda_id, codigo)
);

create index idx_vale_tienda_creado
    on vale(tienda_id, creado_en desc);
