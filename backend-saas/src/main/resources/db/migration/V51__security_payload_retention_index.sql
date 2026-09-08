-- This migration contains only a PostgreSQL non-transactional statement.
-- Flyway detects CREATE INDEX CONCURRENTLY and executes it outside a transaction.
create index concurrently if not exists idx_saas_security_outbox_terminal_retention
    on saas_security_notification_outbox((coalesce(delivered_at, created_at)), id)
    where status in ('DELIVERED', 'ACKNOWLEDGED')
      and encrypted_payload <> '__PURGED__';
