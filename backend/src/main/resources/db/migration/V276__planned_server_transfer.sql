CREATE TABLE server_transfer (
    id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES instalacion(id),
    state varchar(16) NOT NULL CHECK(state IN ('PREPARED','FROZEN','SEALED','ACTIVATED','CANCELLED','RECEIVED','ACTIVE')),
    destination_public_key text NOT NULL,
    destination_key_fingerprint char(64) NOT NULL,
    backup_path text,
    backup_sha256 char(64),
    capsule_sha256 char(64),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX server_transfer_pending ON server_transfer ((true))
    WHERE state IN ('PREPARED','FROZEN','SEALED','ACTIVATED','RECEIVED');

-- Every write transaction takes a shared fence. Freeze takes its exclusive counterpart,
-- waiting for in-flight writers, then publishes the persistent write barrier atomically.
CREATE FUNCTION tpv_server_transfer_write_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(774151001);
    IF EXISTS (SELECT 1 FROM server_transfer WHERE state IN ('FROZEN','SEALED','ACTIVATED','RECEIVED')
        AND id::text IS DISTINCT FROM current_setting('tpv.transfer.maintenance_id',true)) THEN
        RAISE EXCEPTION 'SERVER_TRANSFER_FROZEN' USING ERRCODE = '55000';
    END IF;
    RETURN NULL;
END;
$$;
DO $$
DECLARE target record;
BEGIN
    FOR target IN SELECT schemaname,tablename FROM pg_tables WHERE schemaname = current_schema()
        AND tablename NOT IN ('server_transfer','flyway_schema_history','configuracion_backup','ejecucion_backup')
    LOOP
        EXECUTE format('CREATE TRIGGER server_transfer_write_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I.%I FOR EACH STATEMENT EXECUTE FUNCTION %I.tpv_server_transfer_write_guard()',target.schemaname,target.tablename,target.schemaname);
    END LOOP;
END;
$$;
