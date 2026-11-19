create table inventory_snapshot_sync_checkpoint (
    store_id uuid not null references tienda(id) on delete cascade,
    source_installation_id uuid not null references instalacion(id) on delete cascade,
    captured_at timestamptz not null,
    primary key (store_id, source_installation_id)
);

create index ix_inventory_snapshot_outbox_waiting
    on sync_outbox(tienda_id, empresa_id, (payload ->> 'sourceInstallationId'))
    where tipo_entidad = 'INVENTORY_SNAPSHOT' and estado in ('PENDIENTE', 'ENVIANDO', 'ERROR');
