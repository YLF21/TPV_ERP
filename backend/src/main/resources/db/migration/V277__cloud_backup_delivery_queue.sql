CREATE TABLE cloud_backup_delivery (
    backup_id uuid PRIMARY KEY REFERENCES ejecucion_backup(id),
    installation_id uuid NOT NULL,
    descriptor jsonb NOT NULL,
    spool_sha256 char(64) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','UPLOADING','SENT','RETIRED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    uploaded_at timestamptz,
    spool_purged_at timestamptz,
    error_reason varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((state='UPLOADING') = (lease_token IS NOT NULL)),
    CHECK ((state='UPLOADING') = (lease_until IS NOT NULL))
);
CREATE INDEX ix_cloud_backup_delivery_due ON cloud_backup_delivery(next_attempt_at, created_at)
    WHERE state IN ('PENDING','FAILED','UPLOADING');
