CREATE TABLE member_category_official_snapshot (
    id UUID PRIMARY KEY,
    bootstrap_id UUID NOT NULL,
    source_snapshot_id UUID NOT NULL,
    empresa_id UUID NOT NULL REFERENCES empresa(id),
    tienda_id UUID NOT NULL REFERENCES tienda(id),
    official_company_id UUID NOT NULL,
    config_revision BIGINT NOT NULL,
    assignment_revision BIGINT NOT NULL,
    category_count INTEGER NOT NULL,
    assignment_count INTEGER NOT NULL,
    category_hash VARCHAR(64) NOT NULL,
    assignment_hash VARCHAR(64) NOT NULL,
    snapshot_checksum VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_member_category_official_snapshot_revision UNIQUE (
        bootstrap_id, tienda_id, config_revision, assignment_revision
    ),
    CONSTRAINT ck_member_category_official_snapshot_revision CHECK (
        config_revision > 0 AND assignment_revision > 0
    ),
    CONSTRAINT ck_member_category_official_snapshot_counts CHECK (
        category_count >= 0 AND assignment_count >= 0
    ),
    CONSTRAINT ck_member_category_official_snapshot_status CHECK (
        status IN ('RECEIVED', 'APPLIED')
    )
);

CREATE INDEX idx_member_category_official_snapshot_store
    ON member_category_official_snapshot (
        tienda_id, config_revision DESC, assignment_revision DESC
    );
