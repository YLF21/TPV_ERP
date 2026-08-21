ALTER TABLE member_category_projection_state
    ADD COLUMN config_cursor_id UUID,
    ADD COLUMN assignment_cursor_id UUID;

CREATE TABLE member_category_official_feed_page (
    id UUID PRIMARY KEY,
    empresa_id UUID NOT NULL REFERENCES empresa(id),
    tienda_id UUID NOT NULL REFERENCES tienda(id),
    official_company_id UUID NOT NULL,
    from_config_revision BIGINT NOT NULL,
    from_config_id UUID,
    to_config_revision BIGINT NOT NULL,
    to_config_id UUID,
    from_assignment_revision BIGINT NOT NULL,
    from_assignment_id UUID,
    to_assignment_revision BIGINT NOT NULL,
    to_assignment_id UUID,
    category_count INTEGER NOT NULL,
    assignment_count INTEGER NOT NULL,
    page_checksum VARCHAR(64) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_member_category_feed_revisions CHECK (
        from_config_revision >= 0
        AND to_config_revision >= from_config_revision
        AND from_assignment_revision >= 0
        AND to_assignment_revision >= from_assignment_revision
    ),
    CONSTRAINT ck_member_category_feed_counts CHECK (
        category_count >= 0 AND assignment_count >= 0
    )
);

CREATE INDEX idx_member_category_official_feed_store
    ON member_category_official_feed_page (tienda_id, applied_at DESC);
