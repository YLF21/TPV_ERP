create table manifiesto_autorizacion_documento_venta (
    documento_id uuid primary key,
    tienda_id uuid not null,
    version_formato integer not null default 1,
    algoritmo varchar(16) not null default 'SHA-256',
    huella varchar(64) not null,
    creado_en timestamptz not null,
    actualizado_en timestamptz not null,
    row_version bigint not null default 0,
    constraint manifiesto_autorizacion_documento_venta_documento_fk
        foreign key (documento_id) references documento (id) on delete cascade,
    constraint manifiesto_autorizacion_documento_venta_tienda_fk
        foreign key (tienda_id) references tienda (id) on delete cascade,
    constraint manifiesto_autorizacion_documento_venta_formato_ck
        check (version_formato = 1 and algoritmo = 'SHA-256'),
    constraint manifiesto_autorizacion_documento_venta_huella_ck
        check (huella ~ '^[0-9a-f]{64}$')
);

create table manifiesto_autorizacion_documento_venta_operacion (
    documento_id uuid not null,
    codigo_operacion varchar(64) not null,
    version_politica bigint not null,
    primary key (documento_id, codigo_operacion),
    constraint manifiesto_autorizacion_operacion_documento_fk
        foreign key (documento_id)
        references manifiesto_autorizacion_documento_venta (documento_id)
        on delete cascade,
    constraint manifiesto_autorizacion_operacion_version_ck
        check (version_politica >= 0),
    constraint manifiesto_autorizacion_operacion_codigo_ck
        check (codigo_operacion in (
            'MANUAL_RETURN_WITHOUT_TICKET',
            'TEMPORARY_NAME',
            'TEMPORARY_PRICE_CHANGE',
            'OPEN_PRICE_PRODUCT',
            'APPLY_SALE_DISCOUNT'
        ))
);

create index manifiesto_autorizacion_documento_venta_tienda_idx
    on manifiesto_autorizacion_documento_venta (tienda_id, creado_en desc);
