create table configuracion_ean_interno (
    empresa_id uuid primary key,
    codigo_empresa varchar(2) not null,
    config_version bigint not null default 0,
    creada_en timestamptz not null,
    actualizada_en timestamptz not null,
    row_version bigint not null default 0,
    constraint configuracion_ean_interno_empresa_fk
        foreign key (empresa_id) references empresa (id) on delete cascade,
    constraint configuracion_ean_interno_codigo_empresa_ck
        check (codigo_empresa ~ '^[0-9]{2}$'),
    constraint configuracion_ean_interno_version_ck
        check (config_version >= 0)
);

create table secuencia_ean_interno (
    tienda_id uuid not null,
    formato varchar(8) not null,
    ultimo_numero bigint not null default -1,
    primary key (tienda_id, formato),
    constraint secuencia_ean_interno_tienda_fk
        foreign key (tienda_id) references tienda (id) on delete cascade,
    constraint secuencia_ean_interno_formato_ck
        check (formato in ('EAN_8', 'EAN_13')),
    constraint secuencia_ean_interno_numero_ck
        check (
            (formato = 'EAN_8' and ultimo_numero between -1 and 999)
            or (formato = 'EAN_13' and ultimo_numero between -1 and 999999)
        )
);

create table asignacion_ean_interno (
    id uuid primary key,
    empresa_id uuid not null,
    tienda_id uuid not null,
    producto_id uuid,
    operador_id uuid not null,
    terminal_id uuid not null,
    formato varchar(8) not null,
    codigo varchar(13) not null,
    origen varchar(16) not null,
    estado varchar(16) not null,
    tipo_identificador varchar(32),
    reservada_en timestamptz not null,
    expira_en timestamptz,
    asignada_en timestamptz,
    constraint asignacion_ean_interno_empresa_fk
        foreign key (empresa_id) references empresa (id) on delete cascade,
    constraint asignacion_ean_interno_tienda_fk
        foreign key (tienda_id) references tienda (id) on delete cascade,
    constraint asignacion_ean_interno_producto_fk
        foreign key (producto_id) references producto (id) on delete restrict,
    constraint asignacion_ean_interno_operador_fk
        foreign key (operador_id) references usuario (id) on delete restrict,
    constraint asignacion_ean_interno_terminal_fk
        foreign key (terminal_id) references terminal (id) on delete restrict,
    constraint asignacion_ean_interno_codigo_uq unique (empresa_id, codigo),
    constraint asignacion_ean_interno_formato_ck
        check (formato in ('EAN_8', 'EAN_13')),
    constraint asignacion_ean_interno_origen_ck
        check (origen in ('GENERADO', 'MANUAL')),
    constraint asignacion_ean_interno_estado_ck
        check (estado in ('RESERVADO', 'ASIGNADO')),
    constraint asignacion_ean_interno_tipo_ck
        check (tipo_identificador is null or tipo_identificador in (
            'CODIGO_BARRAS', 'CODIGO_BARRAS_2'
        )),
    constraint asignacion_ean_interno_longitud_ck
        check (
            (formato = 'EAN_8' and codigo ~ '^[0-9]{8}$')
            or (formato = 'EAN_13' and codigo ~ '^[0-9]{13}$')
        ),
    constraint asignacion_ean_interno_estado_fechas_ck
        check (
            (estado = 'RESERVADO' and producto_id is null
                and tipo_identificador is null and expira_en is not null
                and asignada_en is null)
            or (estado = 'ASIGNADO' and producto_id is not null
                and tipo_identificador is not null and expira_en is null
                and asignada_en is not null)
        )
);

create index asignacion_ean_interno_reserva_idx
    on asignacion_ean_interno (tienda_id, formato, expira_en, reservada_en)
    where estado = 'RESERVADO';

create index asignacion_ean_interno_producto_idx
    on asignacion_ean_interno (producto_id, asignada_en desc)
    where estado = 'ASIGNADO';

alter table configuracion_seguridad_operacion_venta_override
    drop constraint config_seguridad_operacion_venta_codigo_ck;

alter table configuracion_seguridad_operacion_venta_override
    add constraint config_seguridad_operacion_venta_codigo_ck
    check (codigo_operacion in (
        'OPEN_CASH_DRAWER',
        'EDIT_CATALOG_PRODUCT',
        'GENERATE_PRODUCT_EAN',
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
        'CONFIRM_MANUAL_CARD_PAYMENT',
        'CONFIRM_TRANSFER_PAYMENT',
        'PAYMENT_TERMINAL_VOID',
        'PAYMENT_TERMINAL_REFUND',
        'PAYMENT_COMPENSATION_ACK'
    ));

alter table intento_autorizacion_operacion_venta
    drop constraint intento_autorizacion_operacion_venta_codigo_ck;

alter table intento_autorizacion_operacion_venta
    add constraint intento_autorizacion_operacion_venta_codigo_ck
    check (codigo_operacion in (
        'OPEN_CASH_DRAWER',
        'EDIT_CATALOG_PRODUCT',
        'GENERATE_PRODUCT_EAN',
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
        'CONFIRM_MANUAL_CARD_PAYMENT',
        'CONFIRM_TRANSFER_PAYMENT',
        'PAYMENT_TERMINAL_VOID',
        'PAYMENT_TERMINAL_REFUND',
        'PAYMENT_COMPENSATION_ACK'
    ));
