create table documento_ajuste (
    id uuid primary key,
    documento_id uuid not null references documento(id),
    tipo varchar(24) not null,
    orden integer not null,
    porcentaje numeric(5,2) not null,
    base_elegible numeric(19,2) not null,
    importe_aplicado numeric(19,2) not null,
    usuario_id uuid references usuario(id),
    creado_en timestamp with time zone not null,
    socio_id uuid,
    categoria_socio_id uuid,
    categoria_socio_nombre varchar(160),
    constraint ck_documento_ajuste_tipo check (tipo in ('MANUAL_PERCENT', 'MEMBER_PERCENT')),
    constraint ck_documento_ajuste_porcentaje check (porcentaje >= 0 and porcentaje <= 100),
    constraint ck_documento_ajuste_base check (base_elegible >= 0),
    constraint ck_documento_ajuste_importe check (importe_aplicado >= 0),
    constraint uq_documento_ajuste_orden unique (documento_id, orden)
);

create index ix_documento_ajuste_documento on documento_ajuste(documento_id);

alter table documento_linea
    add column documento_ajuste_id uuid references documento_ajuste(id),
    add column linea_origen_id uuid references documento_linea(id);

create index ix_documento_linea_ajuste on documento_linea(documento_ajuste_id);
create index ix_documento_linea_origen on documento_linea(linea_origen_id);
