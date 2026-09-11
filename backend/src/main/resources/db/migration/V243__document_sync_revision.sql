-- Publication revisions belong to the same transaction as the document snapshot and its outbox event.
-- The row lock acquired by INSERT/UPDATE serializes publishers without relying on JPA entity versions.
create table documento_sync_revision (
    documento_id uuid primary key references documento(id),
    source_revision bigint not null check (source_revision > 0)
);
