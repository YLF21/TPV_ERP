create table comprobacion_mercancia (
    id uuid primary key,
    documento_id uuid not null references documento(id),
    tienda_id uuid not null references tienda(id),
    estado varchar(24) not null,
    creado_por uuid not null references usuario(id),
    creado_en timestamp with time zone not null,
    cerrado_por uuid references usuario(id),
    cerrado_en timestamp with time zone,
    version bigint not null default 0
);

create unique index ux_comprobacion_mercancia_documento_abierta
    on comprobacion_mercancia(documento_id)
    where estado = 'ABIERTA';

create table comprobacion_mercancia_linea (
    id uuid primary key,
    comprobacion_id uuid not null references comprobacion_mercancia(id) on delete cascade,
    producto_id uuid not null,
    cantidad_esperada integer not null,
    cantidad_registrada integer not null,
    ultimo_usuario_id uuid references usuario(id),
    terminal_id uuid references terminal(id),
    actualizado_en timestamp with time zone,
    version bigint not null default 0,
    constraint ux_comprobacion_mercancia_linea_producto unique (comprobacion_id, producto_id),
    constraint ck_comprobacion_mercancia_linea_esperada check (cantidad_esperada <> 0),
    constraint ck_comprobacion_mercancia_linea_registrada check (cantidad_registrada >= 0)
);
