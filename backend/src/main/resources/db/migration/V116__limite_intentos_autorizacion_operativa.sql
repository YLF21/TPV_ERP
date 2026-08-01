create table intento_autorizacion_operacion_venta (
    id uuid primary key,
    tienda_id uuid not null,
    operador_id uuid not null,
    terminal_id uuid not null,
    codigo_operacion varchar(64) not null,
    fallos_consecutivos integer not null default 0,
    bloqueado_hasta timestamptz,
    ultimo_fallo_en timestamptz,
    actualizada_en timestamptz not null,
    row_version bigint not null default 0,
    constraint intento_autorizacion_operacion_venta_tienda_fk
        foreign key (tienda_id) references tienda (id) on delete cascade,
    constraint intento_autorizacion_operacion_venta_operador_fk
        foreign key (operador_id) references usuario (id) on delete cascade,
    constraint intento_autorizacion_operacion_venta_terminal_fk
        foreign key (terminal_id) references terminal (id) on delete cascade,
    constraint intento_autorizacion_operacion_venta_scope_uq
        unique (tienda_id, operador_id, terminal_id, codigo_operacion),
    constraint intento_autorizacion_operacion_venta_fallos_ck
        check (fallos_consecutivos >= 0),
    constraint intento_autorizacion_operacion_venta_estado_ck
        check (
            (
                fallos_consecutivos = 0
                and ultimo_fallo_en is null
                and bloqueado_hasta is null
            )
            or (
                fallos_consecutivos > 0
                and ultimo_fallo_en is not null
                and (
                    bloqueado_hasta is null
                    or bloqueado_hasta >= ultimo_fallo_en
                )
            )
        ),
    constraint intento_autorizacion_operacion_venta_codigo_ck
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
            'CONFIRM_MANUAL_CARD_PAYMENT',
            'CONFIRM_TRANSFER_PAYMENT',
            'PAYMENT_TERMINAL_VOID',
            'PAYMENT_TERMINAL_REFUND',
            'PAYMENT_COMPENSATION_ACK'
        ))
);

create index intento_autorizacion_operacion_venta_bloqueo_idx
    on intento_autorizacion_operacion_venta (
        tienda_id,
        terminal_id,
        bloqueado_hasta
    )
    where bloqueado_hasta is not null;
