alter table documento_devolucion_pago
    drop constraint if exists chk_documento_devolucion_pago_tipo;

alter table documento_devolucion_pago
    drop constraint if exists chk_documento_devolucion_pago_terminal;

alter table documento_devolucion_pago
    add constraint chk_documento_devolucion_pago_tipo
    check (tipo in ('CASH', 'CARD', 'VOUCHER', 'TRANSFER', 'EXCHANGE'));

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
            tipo in ('CASH', 'VOUCHER', 'EXCHANGE')
            and terminal_operacion_id is null
        )
    );
