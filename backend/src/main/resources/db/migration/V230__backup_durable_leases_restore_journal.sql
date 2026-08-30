-- Backup workers do not hold a database transaction while external tools run.
-- The partial unique index is the cross-node fencing boundary.
ALTER TABLE ejecucion_backup
    ADD COLUMN IF NOT EXISTS worker_token uuid,
    ADD COLUMN IF NOT EXISTS heartbeat_at timestamptz,
    ADD COLUMN IF NOT EXISTS lease_until timestamptz;

UPDATE ejecucion_backup
   SET heartbeat_at = COALESCE(heartbeat_at, finalizada_en, iniciada_en),
       lease_until = COALESCE(lease_until, finalizada_en, iniciada_en)
 WHERE heartbeat_at IS NULL OR lease_until IS NULL;

UPDATE ejecucion_backup
   SET result = 'FALLO', finalizada_en = COALESCE(finalizada_en, now()),
       error_reason = COALESCE(error_reason, 'LEASE_FIELDS_MISSING')
 WHERE result = 'EN_CURSO'
   AND (worker_token IS NULL OR heartbeat_at IS NULL OR lease_until IS NULL);

ALTER TABLE ejecucion_backup
    DROP CONSTRAINT IF EXISTS ck_ejecucion_backup_active_lease_fields;
ALTER TABLE ejecucion_backup
    ADD CONSTRAINT ck_ejecucion_backup_active_lease_fields
    CHECK (result <> 'EN_CURSO' AND result IS NOT NULL
           OR (worker_token IS NOT NULL AND heartbeat_at IS NOT NULL AND lease_until IS NOT NULL));

CREATE UNIQUE INDEX IF NOT EXISTS ux_ejecucion_backup_one_active_lease
    ON ejecucion_backup(configuracion_id)
 WHERE result = 'EN_CURSO';

CREATE INDEX IF NOT EXISTS ix_ejecucion_backup_expired_lease
    ON ejecucion_backup(configuracion_id, lease_until)
 WHERE result = 'EN_CURSO';

-- The offline restore journal is intentionally filesystem-owned. This table is
-- not used for coordination because the database is unavailable during restore.
COMMENT ON COLUMN ejecucion_backup.worker_token IS
    'Opaque fencing token; never expose through API responses or logs';

CREATE TABLE IF NOT EXISTS backup_restore_finalization (
    journal_id uuid PRIMARY KEY,
    backup_sha256 varchar(64) NOT NULL,
    fiscal_mode varchar(16) NOT NULL,
    finalized_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_backup_restore_finalization_hash CHECK (backup_sha256 ~ '^[0-9A-Fa-f]{64}$'),
    CONSTRAINT ck_backup_restore_finalization_mode CHECK (fiscal_mode IN ('NO_VERIFACTU', 'VERIFACTU', 'PRE_SIF', 'MIXED'))
);

CREATE OR REPLACE FUNCTION impedir_mutacion_backup_restore_finalization()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'La finalización de restauración es inmutable'
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS tr_backup_restore_finalization_inmutable
    ON backup_restore_finalization;
CREATE TRIGGER tr_backup_restore_finalization_inmutable
    BEFORE UPDATE OR DELETE ON backup_restore_finalization
    FOR EACH ROW EXECUTE FUNCTION impedir_mutacion_backup_restore_finalization();
