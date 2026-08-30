-- Keyset pagination and administrative projections for the SaaS event log.
create index if not exists ix_saas_sync_event_company_store_received_event
    on saas_sync_event(company_id, store_id, received_at desc, event_id desc);

create index if not exists ix_saas_sync_event_entity_company_store_received
    on saas_sync_event(entity_type, company_id, store_id, received_at desc, event_id desc);

create index if not exists ix_saas_sync_event_entity_id_received
    on saas_sync_event(entity_type, entity_id, received_at desc, event_id desc);

create index if not exists ix_saas_fiscal_status_company_store_received
    on saas_fiscal_status(company_id, store_id, received_at desc);
