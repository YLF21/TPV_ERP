create table configuracion_seguridad_operacion_venta (
    tienda_id uuid primary key,
    config_version bigint not null default 0,
    row_version bigint not null default 0,
    creada_en timestamptz not null,
    actualizada_en timestamptz not null,
    constraint configuracion_seguridad_operacion_venta_tienda_fk
        foreign key (tienda_id) references tienda (id) on delete cascade,
    constraint configuracion_seguridad_operacion_venta_version_ck
        check (config_version >= 0)
);

create table configuracion_seguridad_operacion_venta_override (
    id uuid primary key,
    tienda_id uuid not null,
    codigo_operacion varchar(64) not null,
    requiere_permiso boolean not null,
    requiere_contrasena boolean not null,
    constraint configuracion_seguridad_operacion_venta_override_tienda_fk
        foreign key (tienda_id)
        references configuracion_seguridad_operacion_venta (tienda_id)
        on delete cascade,
    constraint config_seguridad_operacion_venta_codigo_uq
        unique (tienda_id, codigo_operacion),
    constraint config_seguridad_operacion_venta_codigo_ck
        check (codigo_operacion in (
            'OPEN_CASH_DRAWER',
            'EDIT_CATALOG_PRODUCT',
            'CLOSE_CASH_SESSION',
            'CASH_MOVEMENT',
            'RETURN_TICKET',
            'CANCEL_TICKET',
            'CONVERT_TICKET_TO_INVOICE',
            'MANUAL_RETURN_WITHOUT_TICKET',
            'DELETE_PARKED_SALE',
            'TEMPORARY_NAME',
            'TEMPORARY_PRICE_CHANGE',
            'OPEN_PRICE_PRODUCT',
            'APPLY_SALE_DISCOUNT',
            'APPLY_CHECKOUT_DISCOUNT',
            'CREATE_PENDING_RECEIVABLE',
            'CREDIT_OVERRIDE',
            'PAYMENT_TERMINAL_VOID',
            'PAYMENT_TERMINAL_REFUND',
            'PAYMENT_COMPENSATION_ACK'
        ))
);

create index configuracion_seguridad_operacion_venta_override_tienda_idx
    on configuracion_seguridad_operacion_venta_override (tienda_id);
