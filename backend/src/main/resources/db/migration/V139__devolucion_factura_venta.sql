-- APP VENTA can return a sales invoice through a real sales rectification.
-- Keep both persisted security-code whitelists aligned with the public enum.
alter table configuracion_seguridad_operacion_venta_override
    drop constraint if exists config_seguridad_operacion_venta_codigo_ck;

alter table configuracion_seguridad_operacion_venta_override
    add constraint config_seguridad_operacion_venta_codigo_ck
    check (codigo_operacion in (
        'OPEN_CASH_DRAWER',
        'EDIT_CATALOG_PRODUCT',
        'GENERATE_PRODUCT_EAN',
        'CLOSE_CASH_SESSION',
        'CASH_MOVEMENT',
        'RETURN_TICKET',
        'RETURN_SALES_INVOICE',
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
        'RETURN_SALES_INVOICE',
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
