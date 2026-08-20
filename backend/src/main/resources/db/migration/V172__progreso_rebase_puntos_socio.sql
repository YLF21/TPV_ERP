CREATE TABLE member_points_bootstrap_upload (
    snapshot_id UUID PRIMARY KEY REFERENCES member_points_bootstrap_snapshot(snapshot_id),
    bootstrap_id UUID NOT NULL,
    empresa_id UUID NOT NULL REFERENCES empresa(id),
    tienda_id UUID NOT NULL REFERENCES tienda(id),
    seal_sequence BIGINT NOT NULL,
    account_chunk_count INTEGER NOT NULL,
    absorbed_chunk_count INTEGER NOT NULL,
    replay_chunk_count INTEGER NOT NULL,
    account_count INTEGER NOT NULL,
    absorbed_count INTEGER NOT NULL,
    replay_count INTEGER NOT NULL,
    snapshot_checksum VARCHAR(64) NOT NULL,
    next_account_chunk INTEGER NOT NULL DEFAULT 0,
    next_absorbed_chunk INTEGER NOT NULL DEFAULT 0,
    next_replay_chunk INTEGER NOT NULL DEFAULT 0,
    begin_sent_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    official_revision BIGINT,
    central_watermark BIGINT,
    official_total_chunks INTEGER,
    next_official_chunk INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_member_points_bootstrap_upload_counts CHECK (
        seal_sequence >= 0
        AND account_chunk_count >= 0
        AND absorbed_chunk_count >= 0
        AND replay_chunk_count >= 0
        AND account_count >= 0
        AND absorbed_count >= 0
        AND replay_count >= 0
        AND next_account_chunk BETWEEN 0 AND account_chunk_count
        AND next_absorbed_chunk BETWEEN 0 AND absorbed_chunk_count
        AND next_replay_chunk BETWEEN 0 AND replay_chunk_count
        AND next_official_chunk >= 0
    )
);

CREATE UNIQUE INDEX uq_member_points_bootstrap_upload_store
    ON member_points_bootstrap_upload (bootstrap_id, tienda_id);

CREATE TABLE member_points_official_snapshot_account (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES member_points_bootstrap_snapshot(snapshot_id),
    member_id UUID NOT NULL,
    points BIGINT NOT NULL,
    points_debt BIGINT NOT NULL,
    official_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_member_points_official_snapshot_member
        UNIQUE (snapshot_id, member_id),
    CONSTRAINT ck_member_points_official_snapshot_values
        CHECK (points >= 0 AND points_debt >= 0 AND official_revision >= 0)
);

CREATE INDEX idx_member_points_official_snapshot_apply
    ON member_points_official_snapshot_account (snapshot_id, member_id);
