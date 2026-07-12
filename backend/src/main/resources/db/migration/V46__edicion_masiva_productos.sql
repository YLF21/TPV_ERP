create table producto_edicion_masiva (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    codigo varchar(11) not null,
    serie_id uuid not null,
    numero_version integer not null default 1,
    version_anterior_id uuid references producto_edicion_masiva(id) on delete set null,
    nombre varchar(160) not null,
    estado varchar(16) not null default 'PENDING',
    contenido jsonb not null default '[]'::jsonb,
    creado_por uuid not null references usuario(id),
    creado_en timestamptz not null,
    actualizado_por uuid not null references usuario(id),
    actualizado_en timestamptz not null,
    aplicado_por uuid references usuario(id),
    aplicado_en timestamptz,
    version bigint not null default 0,
    constraint ux_producto_edicion_masiva_codigo unique (tienda_id, codigo),
    constraint ux_producto_edicion_masiva_version unique (serie_id, numero_version),
    constraint ck_producto_edicion_masiva_numero_version check (numero_version > 0),
    constraint ck_producto_edicion_masiva_nombre check (btrim(nombre) <> ''),
    constraint ck_producto_edicion_masiva_estado check (estado in ('PENDING', 'APPLIED')),
    constraint ck_producto_edicion_masiva_contenido check (jsonb_typeof(contenido) = 'array'),
    constraint ck_producto_edicion_masiva_aplicado check (
        (estado = 'PENDING' and aplicado_por is null and aplicado_en is null)
        or (estado = 'APPLIED' and aplicado_por is not null and aplicado_en is not null)
    )
);

create index ix_producto_edicion_masiva_tienda_actualizado
    on producto_edicion_masiva (tienda_id, actualizado_en desc);

create table producto_edicion_masiva_comentario (
    id uuid primary key,
    edicion_id uuid not null references producto_edicion_masiva(id) on delete cascade,
    usuario_id uuid not null references usuario(id),
    texto varchar(1000) not null,
    creado_en timestamptz not null,
    constraint ck_producto_edicion_masiva_comentario_texto check (btrim(texto) <> '')
);

create index ix_producto_edicion_masiva_comentario_edicion
    on producto_edicion_masiva_comentario (edicion_id, creado_en);
