-- A manual card refund can be traced either through the linked dataphone
-- operation, the original document payment, or an external reference. The
-- previous constraint only accepted the first case and rejected valid manual
-- refunds already linked to their original card payment.
alter table documento_devolucion_pago
    drop constraint if exists chk_documento_devolucion_pago_terminal;

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
            tipo in ('CASH', 'VOUCHER')
            and terminal_operacion_id is null
        )
    );

-- Keep the database whitelist aligned with the stable public operation codes.
-- REFUND_POLICY_OVERRIDE and REFUND_TENDER_OVERRIDE were introduced after the
-- original constraint and a successful authorization could not be persisted.
alter table intento_autorizacion_operacion_venta
    drop constraint if exists intento_autorizacion_operacion_venta_codigo_ck;

alter table intento_autorizacion_operacion_venta
    add constraint intento_autorizacion_operacion_venta_codigo_ck
    check (codigo_operacion in (
        'OPEN_CASH_DRAWER',
        'EDIT_CATALOG_PRODUCT',
        'GENERATE_PRODUCT_EAN',
        'CLOSE_CASH_SESSION',
        'CASH_MOVEMENT',
        'RETURN_TICKET',
        'REFUND_POLICY_OVERRIDE',
        'REFUND_TENDER_OVERRIDE',
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
