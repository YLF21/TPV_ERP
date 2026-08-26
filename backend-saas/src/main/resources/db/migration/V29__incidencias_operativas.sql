INSERT INTO saas_admin_permission(code)
VALUES ('MANAGE_OPERATIONAL_INCIDENTS');

INSERT INTO saas_admin_role_permission(role_id, permission_code)
SELECT id, 'MANAGE_OPERATIONAL_INCIDENTS'
FROM saas_admin_role
WHERE name = 'ADMIN';

ALTER TABLE saas_member_category_bootstrap
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN last_activity_at TIMESTAMPTZ;

UPDATE saas_member_category_bootstrap bootstrap
SET last_activity_at = greatest(
    bootstrap.created_at,
    coalesce((
        SELECT max(coalesce(snapshot.completed_at, snapshot.created_at))
        FROM saas_member_category_bootstrap_snapshot snapshot
        WHERE snapshot.bootstrap_id = bootstrap.id
    ), bootstrap.created_at),
    coalesce((
        SELECT max(chunk.created_at)
        FROM saas_member_category_bootstrap_chunk chunk
        JOIN saas_member_category_bootstrap_snapshot snapshot
          ON snapshot.snapshot_id = chunk.snapshot_id
        WHERE snapshot.bootstrap_id = bootstrap.id
    ), bootstrap.created_at),
    coalesce((
        SELECT max(store.completed_at)
        FROM saas_member_category_bootstrap_store store
        WHERE store.bootstrap_id = bootstrap.id
    ), bootstrap.created_at)
);

ALTER TABLE saas_member_category_bootstrap
    ALTER COLUMN last_activity_at SET DEFAULT current_timestamp,
    ALTER COLUMN last_activity_at SET NOT NULL;

CREATE OR REPLACE FUNCTION touch_saas_member_category_bootstrap()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.last_activity_at = clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_saas_member_category_bootstrap_touch
BEFORE UPDATE ON saas_member_category_bootstrap
FOR EACH ROW EXECUTE FUNCTION touch_saas_member_category_bootstrap();

CREATE OR REPLACE FUNCTION touch_saas_member_category_bootstrap_from_child()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_bootstrap_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'saas_member_category_bootstrap_chunk' THEN
        SELECT snapshot.bootstrap_id INTO resolved_bootstrap_id
        FROM saas_member_category_bootstrap_snapshot snapshot
        WHERE snapshot.snapshot_id = NEW.snapshot_id;
    ELSE
        resolved_bootstrap_id = NEW.bootstrap_id;
    END IF;

    UPDATE saas_member_category_bootstrap
    SET last_activity_at = clock_timestamp()
    WHERE id = resolved_bootstrap_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_saas_member_category_bootstrap_store_touch
AFTER INSERT OR UPDATE ON saas_member_category_bootstrap_store
FOR EACH ROW EXECUTE FUNCTION touch_saas_member_category_bootstrap_from_child();

CREATE TRIGGER trg_saas_member_category_bootstrap_snapshot_touch
AFTER INSERT OR UPDATE ON saas_member_category_bootstrap_snapshot
FOR EACH ROW EXECUTE FUNCTION touch_saas_member_category_bootstrap_from_child();

CREATE TRIGGER trg_saas_member_category_bootstrap_chunk_touch
AFTER INSERT OR UPDATE ON saas_member_category_bootstrap_chunk
FOR EACH ROW EXECUTE FUNCTION touch_saas_member_category_bootstrap_from_child();

CREATE TABLE saas_operational_incident_command (
    command_id UUID PRIMARY KEY,
    command_type VARCHAR(64) NOT NULL,
    company_id UUID NOT NULL REFERENCES saas_company(id),
    target_id UUID NOT NULL,
    expected_status VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    requested_by VARCHAR(80) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    result_status VARCHAR(24) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_saas_operational_incident_command_type CHECK (
        command_type IN ('CANCEL_MEMBER_CATEGORY_BOOTSTRAP')
    ),
    CONSTRAINT ck_saas_operational_incident_expected_status CHECK (
        expected_status IN ('COLLECTING', 'CONFLICT')
    ),
    CONSTRAINT ck_saas_operational_incident_result_status CHECK (
        result_status IN ('CANCELLED')
    ),
    CONSTRAINT ck_saas_operational_incident_reason CHECK (
        length(trim(reason)) BETWEEN 5 AND 1000
    )
);

CREATE INDEX ix_saas_operational_incident_command_target
    ON saas_operational_incident_command (company_id, target_id, completed_at DESC);

CREATE OR REPLACE FUNCTION reject_saas_operational_incident_command_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'saas_operational_incident_command is append-only';
END;
$$;

CREATE TRIGGER trg_saas_operational_incident_command_append_only
BEFORE UPDATE OR DELETE ON saas_operational_incident_command
FOR EACH ROW EXECUTE FUNCTION reject_saas_operational_incident_command_mutation();
