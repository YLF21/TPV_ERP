-- Queueing a snapshot does not change the business revision or any stock data.
alter table replenishment_request add column sync_enqueued_revision bigint;
alter table replenishment_request add constraint ck_replenishment_sync_revision
    check (sync_enqueued_revision is null or (sync_enqueued_revision >= 0 and sync_enqueued_revision <= version));
create index ix_replenishment_sync_pending on replenishment_request(id)
    where sync_enqueued_revision is null or sync_enqueued_revision < version;
