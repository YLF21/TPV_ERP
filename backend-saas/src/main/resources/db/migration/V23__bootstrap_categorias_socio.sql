CREATE SEQUENCE IF NOT EXISTS saas_member_category_revision_seq;

CREATE TABLE saas_member_category_bootstrap (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id),
    status VARCHAR(24) NOT NULL,
    expected_store_count INTEGER NOT NULL,
    conflict_reason TEXT,
    config_revision BIGINT,
    assignment_revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_saas_member_category_bootstrap_status CHECK (
        status IN ('COLLECTING', 'CONFLICT', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT ck_saas_member_category_bootstrap_counts CHECK (
        expected_store_count > 0
    )
);

CREATE UNIQUE INDEX uq_saas_member_category_bootstrap_active
    ON saas_member_category_bootstrap (company_id)
    WHERE status IN ('COLLECTING', 'CONFLICT');

CREATE TABLE saas_member_category_bootstrap_store (
    id UUID PRIMARY KEY,
    bootstrap_id UUID NOT NULL REFERENCES saas_member_category_bootstrap(id),
    store_id UUID NOT NULL REFERENCES saas_store(id),
    snapshot_id UUID,
    completed_at TIMESTAMPTZ,
    conflict_reason TEXT,
    CONSTRAINT uq_saas_member_category_bootstrap_store
        UNIQUE (bootstrap_id, store_id),
    CONSTRAINT uq_saas_member_category_bootstrap_snapshot
        UNIQUE (bootstrap_id, snapshot_id)
);

CREATE TABLE saas_member_category_bootstrap_snapshot (
    snapshot_id UUID PRIMARY KEY,
    bootstrap_id UUID NOT NULL REFERENCES saas_member_category_bootstrap(id),
    store_id UUID NOT NULL REFERENCES saas_store(id),
    category_chunk_count INTEGER NOT NULL,
    assignment_chunk_count INTEGER NOT NULL,
    category_count INTEGER NOT NULL,
    assignment_count INTEGER NOT NULL,
    category_hash VARCHAR(64) NOT NULL,
    assignment_hash VARCHAR(64) NOT NULL,
    snapshot_checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_saas_member_category_snapshot_counts CHECK (
        category_chunk_count >= 0
        AND assignment_chunk_count >= 0
        AND category_count >= 0
        AND assignment_count >= 0
    )
);

CREATE TABLE saas_member_category_bootstrap_chunk (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES saas_member_category_bootstrap_snapshot(snapshot_id),
    kind VARCHAR(24) NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_hash VARCHAR(64) NOT NULL,
    record_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_saas_member_category_bootstrap_chunk
        UNIQUE (snapshot_id, kind, chunk_index),
    CONSTRAINT ck_saas_member_category_bootstrap_chunk_kind CHECK (
        kind IN ('CATEGORIES', 'ASSIGNMENTS')
    ),
    CONSTRAINT ck_saas_member_category_bootstrap_chunk_values CHECK (
        chunk_index >= 0 AND record_count BETWEEN 1 AND 500
    )
);

CREATE TABLE saas_member_category_bootstrap_category (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES saas_member_category_bootstrap_snapshot(snapshot_id),
    category_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    min_points BIGINT NOT NULL,
    discount_percent NUMERIC(5,2) NOT NULL,
    discount_enabled BOOLEAN NOT NULL,
    manual_only BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT uq_saas_member_category_bootstrap_category
        UNIQUE (snapshot_id, category_id),
    CONSTRAINT ck_saas_member_category_bootstrap_category_values CHECK (
        min_points >= 0 AND discount_percent BETWEEN 0 AND 100
    )
);

CREATE TABLE saas_member_category_bootstrap_assignment (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES saas_member_category_bootstrap_snapshot(snapshot_id),
    member_id UUID NOT NULL,
    category_id UUID NOT NULL,
    lock_automatic BOOLEAN NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    assignment_source VARCHAR(24) NOT NULL,
    CONSTRAINT uq_saas_member_category_bootstrap_assignment
        UNIQUE (snapshot_id, member_id),
    CONSTRAINT ck_saas_member_category_bootstrap_assignment_source CHECK (
        assignment_source IN ('MOVEMENT', 'LEGACY_CURRENT')
    )
);

CREATE TABLE saas_member_category (
    category_id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id),
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    min_points BIGINT NOT NULL,
    discount_percent NUMERIC(5,2) NOT NULL,
    discount_enabled BOOLEAN NOT NULL,
    manual_only BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    config_revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_saas_member_category_code UNIQUE (company_id, code),
    CONSTRAINT ck_saas_member_category_values CHECK (
        min_points >= 0
        AND discount_percent BETWEEN 0 AND 100
        AND config_revision > 0
    )
);

CREATE UNIQUE INDEX uq_saas_member_category_auto_threshold
    ON saas_member_category (company_id, min_points)
    WHERE active AND NOT manual_only;

CREATE TABLE saas_member_category_assignment (
    member_id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id),
    category_id UUID NOT NULL REFERENCES saas_member_category(category_id),
    lock_automatic BOOLEAN NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    assignment_source VARCHAR(24) NOT NULL,
    assignment_revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_saas_member_category_assignment_revision CHECK (
        assignment_revision > 0
    )
);

CREATE INDEX idx_saas_member_category_config_feed
    ON saas_member_category (company_id, config_revision, category_id);

CREATE INDEX idx_saas_member_category_assignment_feed
    ON saas_member_category_assignment (company_id, assignment_revision, member_id);
