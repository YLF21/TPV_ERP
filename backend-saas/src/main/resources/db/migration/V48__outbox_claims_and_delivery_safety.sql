alter table saas_security_notification_outbox
    add column idempotency_key varchar(80),
    add column claimed_at timestamp with time zone,
    add column claim_token uuid;

update saas_security_notification_outbox
set idempotency_key = id::text
where idempotency_key is null;

alter table saas_security_notification_outbox
    alter column idempotency_key set not null,
    add constraint uq_saas_security_notification_idempotency unique (idempotency_key);

drop index idx_saas_security_outbox_pending;
create index idx_saas_security_outbox_delivery
    on saas_security_notification_outbox(status, next_attempt_at, claimed_at, created_at);

alter table saas_integration_run
    add column delivery_attempt_count integer not null default 0,
    add column claimed_at timestamp with time zone,
    add column claim_token uuid,
    add constraint ck_saas_integration_delivery_attempts check (delivery_attempt_count >= 0);

drop index idx_saas_integration_run_pending;
create index idx_saas_integration_run_delivery
    on saas_integration_run(status, next_attempt_at, claimed_at, started_at);
