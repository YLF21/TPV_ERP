CREATE TABLE member_category_projection_state (
    tienda_id UUID PRIMARY KEY REFERENCES tienda(id),
    empresa_id UUID NOT NULL REFERENCES empresa(id),
    status VARCHAR(32) NOT NULL DEFAULT 'LOCAL_ACTIVE',
    bootstrap_id UUID,
    snapshot_id UUID,
    config_revision BIGINT NOT NULL DEFAULT 0,
    assignment_revision BIGINT NOT NULL DEFAULT 0,
    frozen_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_member_category_projection_status CHECK (
        (status = 'LOCAL_ACTIVE'
            AND bootstrap_id IS NULL
            AND snapshot_id IS NULL
            AND frozen_at IS NULL)
        OR
        (status IN ('FROZEN', 'CONFLICT', 'CENTRAL_ACTIVE')
            AND bootstrap_id IS NOT NULL
            AND snapshot_id IS NOT NULL
            AND frozen_at IS NOT NULL)
    ),
    CONSTRAINT ck_member_category_projection_revisions CHECK (
        config_revision >= 0 AND assignment_revision >= 0
    )
);

CREATE TABLE member_category_bootstrap_snapshot (
    snapshot_id UUID PRIMARY KEY,
    bootstrap_id UUID NOT NULL,
    empresa_id UUID NOT NULL REFERENCES empresa(id),
    tienda_id UUID NOT NULL REFERENCES tienda(id),
    category_count INTEGER NOT NULL,
    assignment_count INTEGER NOT NULL,
    category_hash VARCHAR(64) NOT NULL,
    assignment_hash VARCHAR(64) NOT NULL,
    snapshot_checksum VARCHAR(64) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_member_category_bootstrap_store UNIQUE (bootstrap_id, tienda_id),
    CONSTRAINT ck_member_category_bootstrap_counts CHECK (
        category_count >= 0 AND assignment_count >= 0
    )
);

CREATE TABLE member_category_bootstrap_category (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES member_category_bootstrap_snapshot(snapshot_id),
    category_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    min_points BIGINT NOT NULL,
    discount_percent NUMERIC(5,2) NOT NULL,
    discount_enabled BOOLEAN NOT NULL,
    manual_only BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT uq_member_category_bootstrap_category
        UNIQUE (snapshot_id, category_id),
    CONSTRAINT ck_member_category_bootstrap_category_values CHECK (
        min_points >= 0 AND discount_percent BETWEEN 0 AND 100
    )
);

CREATE TABLE member_category_bootstrap_assignment (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES member_category_bootstrap_snapshot(snapshot_id),
    member_id UUID NOT NULL,
    category_id UUID NOT NULL,
    lock_automatic BOOLEAN NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    assignment_source VARCHAR(24) NOT NULL,
    CONSTRAINT uq_member_category_bootstrap_assignment
        UNIQUE (snapshot_id, member_id),
    CONSTRAINT ck_member_category_bootstrap_assignment_source CHECK (
        assignment_source IN ('MOVEMENT', 'LEGACY_CURRENT')
    )
);

CREATE INDEX idx_member_category_bootstrap_category_snapshot
    ON member_category_bootstrap_category (snapshot_id, code, category_id);

CREATE INDEX idx_member_category_bootstrap_assignment_snapshot
    ON member_category_bootstrap_assignment (snapshot_id, member_id);
