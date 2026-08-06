create table autorizacion_cambio_precio_venta (
    id uuid primary key,
    token_hash varchar(64) not null unique,
    empresa_id uuid not null,
    tienda_id uuid not null,
    terminal_id uuid not null,
    operador_id uuid not null,
    autorizador_id uuid not null,
    operador_nombre varchar(128) not null,
    autorizador_nombre varchar(128) not null,
    delegada boolean not null,
    linea_carrito_id varchar(128) not null,
    producto_id uuid not null,
    precio_unitario numeric(19,2) not null,
    version_politica bigint not null,
    emitida_en timestamptz not null,
    expira_en timestamptz not null,
    origen_reserva_tipo varchar(48),
    origen_reserva_id uuid,
    reservada_en timestamptz,
    consumida_en timestamptz,
    row_version bigint not null default 0,
    constraint autorizacion_cambio_precio_empresa_fk
        foreign key (empresa_id) references empresa (id) on delete cascade,
    constraint autorizacion_cambio_precio_tienda_fk
        foreign key (tienda_id) references tienda (id) on delete cascade,
    constraint autorizacion_cambio_precio_terminal_fk
        foreign key (terminal_id) references terminal (id) on delete cascade,
    constraint autorizacion_cambio_precio_operador_fk
        foreign key (operador_id) references usuario (id),
    constraint autorizacion_cambio_precio_autorizador_fk
        foreign key (autorizador_id) references usuario (id),
    constraint autorizacion_cambio_precio_producto_fk
        foreign key (producto_id) references producto (id),
    constraint autorizacion_cambio_precio_token_ck
        check (token_hash ~ '^[0-9a-f]{64}$'),
    constraint autorizacion_cambio_precio_precio_ck
        check (precio_unitario > 0),
    constraint autorizacion_cambio_precio_politica_ck
        check (version_politica >= 0),
    constraint autorizacion_cambio_precio_linea_ck
        check (char_length(trim(linea_carrito_id)) between 1 and 128),
    constraint autorizacion_cambio_precio_reserva_ck
        check ((origen_reserva_tipo is null) = (origen_reserva_id is null)
            and (origen_reserva_tipo is null) = (reservada_en is null)),
    constraint autorizacion_cambio_precio_consumo_ck
        check (consumida_en is null or reservada_en is not null)
);

create index autorizacion_cambio_precio_activa_idx
    on autorizacion_cambio_precio_venta (tienda_id, operador_id, terminal_id, expira_en)
    where consumida_en is null;

create index autorizacion_cambio_precio_reserva_idx
    on autorizacion_cambio_precio_venta (origen_reserva_tipo, origen_reserva_id)
    where origen_reserva_id is not null and consumida_en is null;
