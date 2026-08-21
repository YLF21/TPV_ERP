CREATE TABLE member_points_official_inbox (
    id UUID PRIMARY KEY,
    tienda_id UUID NOT NULL REFERENCES tienda(id),
    empresa_id UUID NOT NULL REFERENCES empresa(id),
    miembro_id UUID NOT NULL,
    points BIGINT NOT NULL,
    points_debt BIGINT NOT NULL,
    official_revision BIGINT NOT NULL,
    official_synced_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_member_points_official_inbox_store_member
        UNIQUE (tienda_id, miembro_id),
    CONSTRAINT ck_member_points_official_inbox_points
        CHECK (points >= 0 AND points_debt >= 0),
    CONSTRAINT ck_member_points_official_inbox_revision
        CHECK (official_revision > 0),
    CONSTRAINT ck_member_points_official_inbox_attempts
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_member_points_official_inbox_pending
    ON member_points_official_inbox (tienda_id, official_revision)
    WHERE applied_at IS NULL;
