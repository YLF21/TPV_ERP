create table venta_aparcada_recuperacion (
    recovery_id uuid primary key,
    venta_aparcada_id uuid not null unique,
    tienda_id uuid not null,
    empresa_id uuid not null,
    usuario_id uuid not null references usuario(id),
    estado varchar(16) not null,
    documento jsonb not null,
    comentario varchar(500),
    creado_en timestamptz not null,
    confirmado_en timestamptz,
    version bigint not null default 0,
    constraint fk_venta_aparcada_recuperacion_tienda_empresa
        foreign key (tienda_id, empresa_id) references tienda(id, empresa_id),
    constraint ck_venta_aparcada_recuperacion_estado
        check (estado in ('CLAIMED', 'ACKNOWLEDGED')),
    constraint ck_venta_aparcada_recuperacion_confirmacion
        check ((estado = 'CLAIMED' and confirmado_en is null)
            or (estado = 'ACKNOWLEDGED' and confirmado_en is not null))
);

create index idx_venta_aparcada_recuperacion_scope
    on venta_aparcada_recuperacion(tienda_id, empresa_id, creado_en desc);
