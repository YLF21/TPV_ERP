ALTER TABLE saas_sync_event
    ADD COLUMN payload_hash VARCHAR(64);

ALTER TABLE saas_sync_event
    ADD COLUMN schema_version INTEGER;

ALTER TABLE saas_sync_event
    ADD COLUMN projection_status VARCHAR(24);

ALTER TABLE saas_sync_event
    ADD COLUMN projected_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE saas_sync_event
    ADD COLUMN projection_error TEXT;

UPDATE saas_sync_event
SET payload_hash = repeat('0', 64),
    schema_version = 1,
    projection_status = 'IGNORED',
    projected_at = received_at;

ALTER TABLE saas_sync_event
    ALTER COLUMN payload_hash SET NOT NULL;

ALTER TABLE saas_sync_event
    ALTER COLUMN schema_version SET NOT NULL;

ALTER TABLE saas_sync_event
    ALTER COLUMN projection_status SET NOT NULL;

ALTER TABLE saas_sync_event
    ADD CONSTRAINT ck_saas_sync_event_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE saas_sync_event
    ADD CONSTRAINT ck_saas_sync_event_schema_version
        CHECK (schema_version > 0);

ALTER TABLE saas_sync_event
    ADD CONSTRAINT ck_saas_sync_event_projection_status
        CHECK (projection_status IN ('RECEIVED', 'PROJECTED', 'IGNORED', 'ERROR'));

CREATE INDEX idx_saas_sync_event_projection_status
    ON saas_sync_event(projection_status, received_at);

CREATE TABLE saas_sync_event_lock (
    event_id UUID PRIMARY KEY
);

CREATE TABLE saas_member_wallet_projection_lock (
    lock_key VARCHAR(200) PRIMARY KEY
);

ALTER TABLE saas_member_balance_lot
    ADD COLUMN company_id UUID;

UPDATE saas_member_balance_lot lot
SET company_id = account.company_id
FROM saas_member_balance_account account
WHERE account.id = lot.account_id;

ALTER TABLE saas_member_balance_lot
    ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE saas_member_balance_lot
    ADD CONSTRAINT fk_saas_member_balance_lot_company
        FOREIGN KEY (company_id) REFERENCES saas_company(id);

CREATE INDEX idx_saas_member_balance_lot_company
    ON saas_member_balance_lot(company_id, id);

CREATE UNIQUE INDEX uk_saas_member_balance_lot_company_source_movement
    ON saas_member_balance_lot(company_id, source_movement_id)
    WHERE source_movement_id IS NOT NULL;
