create table producto_regla_precio (
    id uuid primary key,
    empresa_id uuid not null references empresa(id) on delete cascade,
    nombre varchar(160) not null,
    formularios jsonb not null,
    creado_por uuid not null references usuario(id),
    creado_en timestamptz not null,
    actualizado_en timestamptz not null,
    version bigint not null default 0,
    constraint ck_producto_regla_precio_nombre check (btrim(nombre) <> ''),
    constraint ck_producto_regla_precio_formularios check (
        jsonb_typeof(formularios) = 'array' and jsonb_array_length(formularios) > 0
    )
);

create index ix_producto_regla_precio_empresa_actualizado
    on producto_regla_precio (empresa_id, actualizado_en desc);
