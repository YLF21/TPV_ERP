-- A browser may retry after losing the receipt. Keep the key with the lead so
-- identical retries remain safe across restarts and multiple API instances.
alter table saas_marketing_demo_request
    add column idempotency_key uuid,
    add column request_fingerprint varchar(64),
    add constraint ck_marketing_demo_request_idempotency_pair check (
        (idempotency_key is null and request_fingerprint is null)
        or (idempotency_key is not null and request_fingerprint is not null
            and request_fingerprint ~ '^[0-9a-f]{64}$')
    );

create unique index uq_marketing_demo_request_idempotency
    on saas_marketing_demo_request(idempotency_key);

-- All-status pages also need deterministic newest-first access.
create index idx_marketing_demo_request_created
    on saas_marketing_demo_request(created_at desc, id desc);
