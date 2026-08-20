ALTER TABLE member_points_projection_state
    ADD COLUMN IF NOT EXISTS official_revision BIGINT NOT NULL DEFAULT 0;

DO $$
DECLARE
    current_constraint TEXT;
BEGIN
    SELECT con.conname
    INTO current_constraint
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = current_schema()
      AND rel.relname = 'member_points_projection_state'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) ILIKE '%LOCAL_ACTIVE%'
      AND pg_get_constraintdef(con.oid) ILIKE '%bootstrap_id%'
    LIMIT 1;

    IF current_constraint IS NOT NULL THEN
        EXECUTE format(
                'ALTER TABLE member_points_projection_state DROP CONSTRAINT %I',
                current_constraint);
    END IF;
END
$$;

ALTER TABLE member_points_projection_state
    ADD CONSTRAINT ck_member_points_projection_state_status_v170 CHECK (
        (status = 'LOCAL_ACTIVE'
            AND bootstrap_id IS NULL
            AND snapshot_id IS NULL
            AND cutoff_at IS NULL
            AND cut_sequence IS NULL)
        OR
        (status IN (
                'FROZEN', 'WAITING_OFFICIAL', 'CATCHING_UP',
                'CONFLICT', 'CENTRAL_ACTIVE')
            AND bootstrap_id IS NOT NULL
            AND snapshot_id IS NOT NULL
            AND cutoff_at IS NOT NULL
            AND cut_sequence IS NOT NULL)
    ),
    ADD CONSTRAINT ck_member_points_official_revision_nonnegative_v170
        CHECK (official_revision >= 0);
