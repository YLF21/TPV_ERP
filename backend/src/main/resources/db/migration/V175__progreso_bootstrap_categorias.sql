CREATE TABLE member_category_bootstrap_upload (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    store_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    central_bootstrap_id UUID,
    status VARCHAR(24) NOT NULL,
    category_chunks_uploaded INTEGER NOT NULL DEFAULT 0,
    assignment_chunks_uploaded INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_member_category_bootstrap_upload_snapshot UNIQUE (snapshot_id),
    CONSTRAINT fk_member_category_bootstrap_upload_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES member_category_bootstrap_snapshot (snapshot_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_member_category_bootstrap_upload_status CHECK (
        status IN ('PENDING', 'UPLOADING', 'WAITING_CENTRAL', 'APPLIED', 'CONFLICT')
    ),
    CONSTRAINT ck_member_category_bootstrap_upload_category_chunks
        CHECK (category_chunks_uploaded >= 0),
    CONSTRAINT ck_member_category_bootstrap_upload_assignment_chunks
        CHECK (assignment_chunks_uploaded >= 0),
    CONSTRAINT ck_member_category_bootstrap_upload_attempts
        CHECK (attempts >= 0)
);

CREATE INDEX idx_member_category_bootstrap_upload_due
    ON member_category_bootstrap_upload (status, next_attempt_at, updated_at)
    WHERE status IN ('PENDING', 'UPLOADING', 'WAITING_CENTRAL');

CREATE INDEX idx_member_category_bootstrap_upload_scope
    ON member_category_bootstrap_upload (company_id, store_id, created_at DESC);
