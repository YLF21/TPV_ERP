CREATE TABLE disaster_recovery (
    id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES instalacion(id),
    backup_id uuid NOT NULL,
    backup_sha256 char(64) NOT NULL,
    state varchar(32) NOT NULL CHECK(state IN ('QUARANTINED','ACTIVE','COMPLETE')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX disaster_recovery_one_pending ON disaster_recovery((true)) WHERE state <> 'COMPLETE';
CREATE FUNCTION tpv_disaster_recovery_write_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM disaster_recovery WHERE state = 'QUARANTINED'
        AND id::text IS DISTINCT FROM current_setting('tpv.recovery.maintenance_id',true)) THEN
        RAISE EXCEPTION 'DISASTER_RECOVERY_QUARANTINED' USING ERRCODE='55000';
    END IF;
    RETURN NULL;
END;
$$;
DO $$
DECLARE target record;
BEGIN
    FOR target IN SELECT schemaname,tablename FROM pg_tables WHERE schemaname=current_schema()
        AND tablename NOT IN ('disaster_recovery','flyway_schema_history')
    LOOP
        EXECUTE format('CREATE TRIGGER disaster_recovery_write_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I.%I FOR EACH STATEMENT EXECUTE FUNCTION %I.tpv_disaster_recovery_write_guard()',target.schemaname,target.tablename,target.schemaname);
    END LOOP;
END;
$$;
