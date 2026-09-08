-- Forward-only operational recovery. Indexes are additive and partial: no live
-- delivery index is dropped or rebuilt during this migration.

alter table saas_security_notification_outbox
    drop constraint ck_saas_security_notification_status,
    add constraint ck_saas_security_notification_status
        check (status in ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED', 'ACKNOWLEDGED'));

alter table saas_integration_run
    drop constraint ck_saas_integration_run_status,
    add constraint ck_saas_integration_run_status
        check(status in ('RUNNING', 'PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'ACKNOWLEDGED'));

-- V48 introduced claim columns after PROCESSING rows could already exist. Reset
-- only orphan claims lacking an owner token/time; stable idempotency keys make
-- the resulting at-least-once retry explicit and safe for consumers.
update saas_security_notification_outbox
   set status = 'PENDING', attempt_count = 0, next_attempt_at = current_timestamp,
       claimed_at = null, claim_token = null, last_error = 'RECOVERED_ORPHAN_CLAIM'
 where status = 'PROCESSING' and (claimed_at is null or claim_token is null);

update saas_integration_run
   set status = 'PENDING', delivery_attempt_count = 0, next_attempt_at = current_timestamp,
       claimed_at = null, claim_token = null, completed_at = null,
       error_code = 'RECOVERED_ORPHAN_CLAIM',
       error_message = 'Reencolada por V50 al detectar un claim sin propietario'
 where status = 'PROCESSING' and (claimed_at is null or claim_token is null);

create index if not exists idx_saas_security_outbox_failed
    on saas_security_notification_outbox(created_at, id)
    where status = 'FAILED';

create index if not exists idx_saas_integration_run_failed_delivery
    on saas_integration_run(started_at, id)
    where status = 'FAILED' and delivery_attempt_count > 0;
