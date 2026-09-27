-- Resolve authorizers by the persisted checkout/document link without scanning the audit history.
create index ix_auditoria_sale_authorization_source
    on auditoria (tienda_id, (datos ->> 'sourceType'), (datos ->> 'sourceId'),
                  (datos ->> 'operationCode'))
    where event = 'SALE_OPERATION_AUTHORIZED' and result = 'EXITO';

create index ix_pos_cash_checkout_document
    on pos_cash_checkout (store_id, documento_id)
    where documento_id is not null;

create index ix_pos_card_checkout_document
    on pos_card_checkout (requested_store_id, documento_id)
    where documento_id is not null;

create index ix_sale_payment_session_ticket
    on sale_payment_session (store_id, ticket_id)
    where ticket_id is not null;
