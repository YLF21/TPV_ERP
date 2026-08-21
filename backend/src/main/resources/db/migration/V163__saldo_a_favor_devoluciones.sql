alter table miembro
    add column return_credit_balance numeric(19,2) not null default 0,
    add column official_return_credit_balance numeric(19,2) not null default 0,
    add constraint ck_miembro_return_credit_balance
        check (return_credit_balance >= 0),
    add constraint ck_miembro_official_return_credit_balance
        check (official_return_credit_balance >= 0);

alter table member_balance_lot
    add column balance_type varchar(24) not null default 'LOYALTY',
    add constraint ck_member_balance_lot_type
        check (balance_type in ('LOYALTY', 'RETURN_CREDIT'));

create index ix_member_balance_lot_member_type_available
    on member_balance_lot(miembro_id, balance_type, expires_at, created_at)
    where amount_remaining > 0 and expired_at is null;

alter table member_movement
    drop constraint if exists ck_member_movement_type;

alter table member_movement
    add constraint ck_member_movement_type check (type in (
        'ALTA_MIEMBRO', 'DESACTIVACION_MIEMBRO', 'CAMBIO_CATEGORIA',
        'ACUMULACION_PUNTOS', 'ACUMULACION_SALDO', 'USO_SALDO',
        'CADUCIDAD_SALDO', 'AJUSTE_MANUAL_SALDO', 'AJUSTE_MANUAL_PUNTOS',
        'AJUSTE_SAAS', 'ANULACION_ACUMULACION_PUNTOS',
        'ANULACION_ACUMULACION_SALDO', 'ANULACION_USO_SALDO',
        'DEVOLUCION_ACUMULACION_PUNTOS', 'DEVOLUCION_ACUMULACION_SALDO',
        'DEVOLUCION_RESTAURACION_SALDO', 'PAGO_DEUDA_PUNTOS',
        'PAGO_DEUDA_SALDO', 'ABONO_CREDITO_DEVOLUCION',
        'USO_CREDITO_DEVOLUCION', 'CADUCIDAD_CREDITO_DEVOLUCION'
    ));

alter table documento_devolucion_pago
    drop constraint if exists chk_documento_devolucion_pago_tipo;

alter table documento_devolucion_pago
    drop constraint if exists chk_documento_devolucion_pago_terminal;

alter table documento_devolucion_pago
    add constraint chk_documento_devolucion_pago_tipo
    check (tipo in (
        'CASH', 'CARD', 'VOUCHER', 'TRANSFER', 'EXCHANGE', 'MEMBER_CREDIT'
    ));

alter table documento_devolucion_pago
    add constraint chk_documento_devolucion_pago_terminal
    check (
        (
            tipo = 'CARD'
            and (
                terminal_operacion_id is not null
                or documento_pago_original_id is not null
                or nullif(btrim(referencia), '') is not null
            )
        )
        or (
            tipo = 'TRANSFER'
            and terminal_operacion_id is null
            and (
                documento_pago_original_id is not null
                or nullif(btrim(referencia), '') is not null
            )
        )
        or (
            tipo in ('CASH', 'VOUCHER', 'EXCHANGE', 'MEMBER_CREDIT')
            and terminal_operacion_id is null
        )
    );
