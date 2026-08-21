ALTER TABLE saas_sync_event ADD COLUMN store_sequence BIGINT;
ALTER TABLE saas_sync_event ADD CONSTRAINT ck_saas_sync_event_store_sequence_positive
    CHECK (store_sequence IS NULL OR store_sequence > 0);
CREATE INDEX ix_saas_sync_event_store_sequence
    ON saas_sync_event(company_id, store_id, store_sequence) WHERE store_sequence IS NOT NULL;

ALTER TABLE saas_member_points_operation
    ADD COLUMN store_id UUID;
ALTER TABLE saas_member_points_operation
    ADD COLUMN store_sequence BIGINT;
ALTER TABLE saas_member_points_operation
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1;
UPDATE saas_member_points_operation operation
SET store_id = event.store_id, store_sequence = event.store_sequence
FROM saas_sync_event event WHERE event.event_id = operation.source_event_id;
ALTER TABLE saas_member_points_operation
    ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE saas_member_points_operation
    ADD CONSTRAINT fk_saas_member_points_operation_store FOREIGN KEY (store_id) REFERENCES saas_store(id);
ALTER TABLE saas_member_points_operation
    ADD CONSTRAINT ck_saas_member_points_operation_store_sequence CHECK (store_sequence IS NULL OR store_sequence > 0);
ALTER TABLE saas_member_points_operation
    ADD CONSTRAINT ck_saas_member_points_operation_schema_version CHECK (schema_version > 0);
CREATE UNIQUE INDEX uq_saas_member_points_operation_store_sequence
    ON saas_member_points_operation(company_id, store_id, store_sequence) WHERE store_sequence IS NOT NULL;
ALTER TABLE saas_member_points_operation
    DROP CONSTRAINT ck_saas_member_points_operation_status;
ALTER TABLE saas_member_points_operation
    DROP CONSTRAINT ck_saas_member_points_operation_resolution;
ALTER TABLE saas_member_points_operation
    ADD CONSTRAINT ck_saas_member_points_operation_status CHECK
        (status IN ('PENDING_BOOTSTRAP','PENDING_DEPENDENCY','ABSORBED_BOOTSTRAP','APPLIED','CONFLICT'));
ALTER TABLE saas_member_points_operation
    ADD CONSTRAINT ck_saas_member_points_operation_resolution CHECK
        ((status IN ('PENDING_BOOTSTRAP','PENDING_DEPENDENCY') AND resolved_at IS NULL)
        OR (status IN ('ABSORBED_BOOTSTRAP','APPLIED','CONFLICT') AND resolved_at IS NOT NULL));

ALTER TABLE saas_member_points_debt_lot ADD COLUMN origin_kind VARCHAR(40);
UPDATE saas_member_points_debt_lot SET origin_kind = origin_type;
ALTER TABLE saas_member_points_debt_lot
    ALTER COLUMN origin_kind SET NOT NULL;
ALTER TABLE saas_member_points_debt_lot
    ALTER COLUMN origin_type DROP NOT NULL;
ALTER TABLE saas_member_points_debt_lot
    DROP CONSTRAINT ck_saas_member_points_debt_origin_type;
ALTER TABLE saas_member_points_debt_lot
    ADD CONSTRAINT ck_saas_member_points_debt_legacy_origin_type
        CHECK (origin_type IS NULL OR origin_type IN ('RETURN_REVERSAL','SALE_CANCELLATION'));
ALTER TABLE saas_member_points_debt_lot
    ADD CONSTRAINT ck_saas_member_points_debt_origin_kind
        CHECK (origin_kind IN ('RETURN_REVERSAL','SALE_CANCELLATION','BOOTSTRAP_OPENING'));

CREATE TABLE saas_member_points_bootstrap (
    id UUID PRIMARY KEY, company_id UUID NOT NULL REFERENCES saas_company(id),
    status VARCHAR(24) NOT NULL, cutoff_at TIMESTAMPTZ, conflict_reason TEXT,
    official_revision BIGINT, central_watermark BIGINT,
    created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ, cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_points_bootstrap_status CHECK (status IN
        ('COLLECTING','CATCHING_UP','RECONCILING','CONFLICT','COMPLETED','CANCELLED')),
    CONSTRAINT ck_points_bootstrap_official CHECK
        ((status='COMPLETED' AND official_revision IS NOT NULL AND central_watermark IS NOT NULL AND completed_at IS NOT NULL)
        OR status<>'COMPLETED'));
CREATE UNIQUE INDEX uq_points_bootstrap_active_company ON saas_member_points_bootstrap(company_id)
    WHERE status IN ('COLLECTING','CATCHING_UP','RECONCILING');
CREATE INDEX ix_points_bootstrap_company_created ON saas_member_points_bootstrap(company_id, created_at DESC);

CREATE TABLE saas_member_points_bootstrap_store (
    id UUID PRIMARY KEY, bootstrap_id UUID NOT NULL REFERENCES saas_member_points_bootstrap(id),
    store_id UUID NOT NULL REFERENCES saas_store(id), completed_at TIMESTAMPTZ, conflict_reason TEXT,
    CONSTRAINT uq_points_bootstrap_store UNIQUE (bootstrap_id, store_id));

CREATE TABLE saas_member_points_bootstrap_snapshot (
    id UUID PRIMARY KEY, bootstrap_id UUID NOT NULL REFERENCES saas_member_points_bootstrap(id),
    snapshot_id UUID NOT NULL, store_id UUID NOT NULL REFERENCES saas_store(id), cutoff_at TIMESTAMPTZ NOT NULL,
    account_chunk_count INTEGER NOT NULL, absorbed_chunk_count INTEGER NOT NULL, replay_chunk_count INTEGER NOT NULL,
    account_count INTEGER NOT NULL, absorbed_count INTEGER NOT NULL, replay_count INTEGER NOT NULL,
    snapshot_checksum CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ, conflict_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_points_bootstrap_snapshot_id UNIQUE (bootstrap_id,snapshot_id),
    CONSTRAINT uq_points_bootstrap_snapshot_store UNIQUE (bootstrap_id,store_id),
    CONSTRAINT ck_points_bootstrap_snapshot_counts CHECK
        (account_chunk_count>=0 AND absorbed_chunk_count>=0 AND replay_chunk_count>=0
        AND account_count>=0 AND absorbed_count>=0 AND replay_count>=0),
    CONSTRAINT ck_points_bootstrap_snapshot_hash CHECK (snapshot_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_points_bootstrap_snapshot_status CHECK (status IN ('COLLECTING','COMPLETED','CONFLICT')));

CREATE TABLE saas_member_points_bootstrap_chunk (
    id UUID PRIMARY KEY, snapshot_row_id UUID NOT NULL REFERENCES saas_member_points_bootstrap_snapshot(id),
    kind VARCHAR(24) NOT NULL, chunk_index INTEGER NOT NULL, chunk_hash CHAR(64) NOT NULL, record_count INTEGER NOT NULL,
    CONSTRAINT uq_points_bootstrap_chunk UNIQUE (snapshot_row_id,kind,chunk_index),
    CONSTRAINT ck_points_bootstrap_chunk_kind CHECK (kind IN ('ACCOUNTS','ABSORBED_OPERATIONS','REPLAY_OPERATIONS')),
    CONSTRAINT ck_points_bootstrap_chunk_index CHECK (chunk_index>=0),
    CONSTRAINT ck_points_bootstrap_chunk_records CHECK (record_count BETWEEN 1 AND 500),
    CONSTRAINT ck_points_bootstrap_chunk_hash CHECK (chunk_hash ~ '^[0-9a-f]{64}$'));

CREATE TABLE saas_member_points_bootstrap_staging_account (
    id UUID PRIMARY KEY, snapshot_row_id UUID NOT NULL REFERENCES saas_member_points_bootstrap_snapshot(id),
    member_id UUID NOT NULL, points NUMERIC(19,0) NOT NULL, points_debt NUMERIC(19,0) NOT NULL,
    CONSTRAINT uq_points_bootstrap_staging_account UNIQUE (snapshot_row_id,member_id),
    CONSTRAINT ck_points_bootstrap_staging_account CHECK (points>=0 AND points_debt>=0));

CREATE TABLE saas_member_points_bootstrap_staging_operation (
    id UUID PRIMARY KEY, snapshot_row_id UUID NOT NULL REFERENCES saas_member_points_bootstrap_snapshot(id),
    kind VARCHAR(24) NOT NULL, operation_id UUID NOT NULL, contract_hash CHAR(64) NOT NULL, source_sequence BIGINT,
    CONSTRAINT uq_points_bootstrap_staging_operation UNIQUE (snapshot_row_id,kind,operation_id),
    CONSTRAINT ck_points_bootstrap_staging_operation_kind CHECK (kind IN ('ABSORBED_OPERATIONS','REPLAY_OPERATIONS')),
    CONSTRAINT ck_points_bootstrap_staging_operation_hash CHECK (contract_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_points_bootstrap_staging_operation_sequence CHECK (source_sequence IS NULL OR source_sequence>0));

CREATE TABLE saas_member_points_bootstrap_absorbed_operation (
    id UUID PRIMARY KEY, bootstrap_id UUID NOT NULL REFERENCES saas_member_points_bootstrap(id),
    company_id UUID NOT NULL REFERENCES saas_company(id), operation_id UUID NOT NULL,
    contract_hash CHAR(64) NOT NULL, source_store_ids TEXT NOT NULL, absorbed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_points_bootstrap_absorbed UNIQUE (company_id,operation_id),
    CONSTRAINT ck_points_bootstrap_absorbed_hash CHECK (contract_hash ~ '^[0-9a-f]{64}$'));

CREATE TABLE saas_member_points_opening (
    id UUID PRIMARY KEY, bootstrap_id UUID NOT NULL REFERENCES saas_member_points_bootstrap(id),
    company_id UUID NOT NULL REFERENCES saas_company(id), member_id UUID NOT NULL,
    points NUMERIC(19,0) NOT NULL, points_debt NUMERIC(19,0) NOT NULL,
    source_store_ids TEXT NOT NULL, source_checksum CHAR(64) NOT NULL, applied_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_points_opening_member UNIQUE (bootstrap_id,member_id),
    CONSTRAINT ck_points_opening_values CHECK (points>=0 AND points_debt>=0),
    CONSTRAINT ck_points_opening_hash CHECK (source_checksum ~ '^[0-9a-f]{64}$'));

CREATE TABLE saas_member_points_official_account (
    id UUID PRIMARY KEY, bootstrap_id UUID NOT NULL REFERENCES saas_member_points_bootstrap(id),
    company_id UUID NOT NULL REFERENCES saas_company(id), member_id UUID NOT NULL,
    points NUMERIC(19,0) NOT NULL, points_debt NUMERIC(19,0) NOT NULL,
    revision BIGINT NOT NULL, central_watermark BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_points_official_member UNIQUE (bootstrap_id,member_id),
    CONSTRAINT ck_points_official_values CHECK (points>=0 AND points_debt>=0));
CREATE INDEX ix_points_bootstrap_staging_account ON saas_member_points_bootstrap_staging_account(snapshot_row_id,member_id);
CREATE INDEX ix_points_bootstrap_staging_operation ON saas_member_points_bootstrap_staging_operation(snapshot_row_id,kind,operation_id);
CREATE INDEX ix_points_official_download ON saas_member_points_official_account(bootstrap_id,member_id);
