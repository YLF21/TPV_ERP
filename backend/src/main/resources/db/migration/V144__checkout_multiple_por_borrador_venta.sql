alter table customer_pending_sale_checkout
    drop constraint if exists customer_pending_sale_checkout_document_id_key;

create index if not exists idx_customer_pending_sale_checkout_document
    on customer_pending_sale_checkout(document_id)
    where document_id is not null;
