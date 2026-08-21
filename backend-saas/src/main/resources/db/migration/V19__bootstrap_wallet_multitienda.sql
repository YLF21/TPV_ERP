CREATE TABLE saas_member_wallet_bootstrap (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id),
    status VARCHAR(20) NOT NULL,
    cutoff_at TIMESTAMP WITH TIME ZONE,
    conflict_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_wallet_bootstrap_status
        CHECK (status IN ('COLLECTING', 'RECONCILING', 'CONFLICT', 'COMPLETED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uk_wallet_bootstrap_active_company
    ON saas_member_wallet_bootstrap(company_id)
    WHERE status IN ('COLLECTING', 'RECONCILING');

CREATE INDEX idx_wallet_bootstrap_company_created
    ON saas_member_wallet_bootstrap(company_id, created_at DESC);

CREATE TABLE saas_member_wallet_bootstrap_store (
    id UUID PRIMARY KEY,
    bootstrap_id UUID NOT NULL REFERENCES saas_member_wallet_bootstrap(id),
    store_id UUID NOT NULL REFERENCES saas_store(id),
    completed_at TIMESTAMP WITH TIME ZONE,
    conflict_reason TEXT,
    CONSTRAINT uk_wallet_bootstrap_expected_store UNIQUE (bootstrap_id, store_id)
);

CREATE TABLE saas_member_wallet_bootstrap_snapshot (
    id UUID PRIMARY KEY,
    bootstrap_id UUID NOT NULL REFERENCES saas_member_wallet_bootstrap(id),
    snapshot_id UUID NOT NULL,
    store_id UUID NOT NULL REFERENCES saas_store(id),
    cutoff_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_chunk_count INTEGER NOT NULL,
    lot_chunk_count INTEGER NOT NULL,
    account_count INTEGER NOT NULL,
    lot_count INTEGER NOT NULL,
    snapshot_checksum VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    conflict_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_wallet_bootstrap_snapshot_id UNIQUE (bootstrap_id, snapshot_id),
    CONSTRAINT uk_wallet_bootstrap_snapshot_store UNIQUE (bootstrap_id, store_id),
    CONSTRAINT ck_wallet_bootstrap_snapshot_counts
        CHECK (account_chunk_count >= 0 AND lot_chunk_count >= 0
            AND account_count >= 0 AND lot_count >= 0),
    CONSTRAINT ck_wallet_bootstrap_snapshot_hash
        CHECK (snapshot_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wallet_bootstrap_snapshot_status
        CHECK (status IN ('COLLECTING', 'COMPLETED', 'CONFLICT'))
);

CREATE TABLE saas_member_wallet_bootstrap_chunk (
    id UUID PRIMARY KEY,
    snapshot_row_id UUID NOT NULL REFERENCES saas_member_wallet_bootstrap_snapshot(id),
    kind VARCHAR(10) NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_hash VARCHAR(64) NOT NULL,
    record_count INTEGER NOT NULL,
    CONSTRAINT uk_wallet_bootstrap_chunk UNIQUE (snapshot_row_id, kind, chunk_index),
    CONSTRAINT ck_wallet_bootstrap_chunk_kind CHECK (kind IN ('ACCOUNTS', 'LOTS')),
    CONSTRAINT ck_wallet_bootstrap_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_wallet_bootstrap_chunk_records CHECK (record_count BETWEEN 1 AND 500),
    CONSTRAINT ck_wallet_bootstrap_chunk_hash CHECK (chunk_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE saas_member_wallet_bootstrap_staging_account (
    id UUID PRIMARY KEY,
    snapshot_row_id UUID NOT NULL REFERENCES saas_member_wallet_bootstrap_snapshot(id),
    member_id UUID NOT NULL,
    loyalty_balance NUMERIC(19, 2) NOT NULL,
    return_credit_balance NUMERIC(19, 2) NOT NULL,
    CONSTRAINT uk_wallet_bootstrap_staging_account UNIQUE (snapshot_row_id, member_id),
    CONSTRAINT ck_wallet_bootstrap_staging_account_balances
        CHECK (loyalty_balance >= 0 AND return_credit_balance >= 0)
);

CREATE TABLE saas_member_wallet_bootstrap_staging_lot (
    id UUID PRIMARY KEY,
    snapshot_row_id UUID NOT NULL REFERENCES saas_member_wallet_bootstrap_snapshot(id),
    lot_id UUID NOT NULL,
    member_id UUID NOT NULL,
    balance_type VARCHAR(20) NOT NULL,
    original_amount NUMERIC(19, 2) NOT NULL,
    remaining_amount NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    source_movement_id UUID,
    document_id UUID,
    CONSTRAINT uk_wallet_bootstrap_staging_lot UNIQUE (snapshot_row_id, lot_id),
    CONSTRAINT ck_wallet_bootstrap_staging_lot_type
        CHECK (balance_type IN ('LOYALTY', 'RETURN_CREDIT')),
    CONSTRAINT ck_wallet_bootstrap_staging_lot_amounts
        CHECK (original_amount > 0
            AND remaining_amount >= 0
            AND remaining_amount <= original_amount)
);

CREATE INDEX idx_wallet_bootstrap_staging_account_member
    ON saas_member_wallet_bootstrap_staging_account(snapshot_row_id, member_id);

CREATE INDEX idx_wallet_bootstrap_staging_lot_identity
    ON saas_member_wallet_bootstrap_staging_lot(snapshot_row_id, lot_id, source_movement_id);
