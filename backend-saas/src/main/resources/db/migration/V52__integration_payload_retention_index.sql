-- This migration contains only a PostgreSQL non-transactional statement.
-- Flyway detects CREATE INDEX CONCURRENTLY and executes it outside a transaction.
create index concurrently if not exists idx_saas_integration_run_terminal_retention
    on saas_integration_run((coalesce(completed_at, started_at)), id)
    where status in ('SUCCEEDED', 'ACKNOWLEDGED')
      and payload <> '__PURGED__';
