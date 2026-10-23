CREATE TABLE saas_cloud_backup (
    backup_id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES saas_installation(installation_id),
    company_id uuid NOT NULL REFERENCES saas_company(id),
    store_id uuid NOT NULL REFERENCES saas_store(id),
    sequence_number bigint NOT NULL CHECK (sequence_number > 0),
    captured_at timestamptz NOT NULL,
    application_version varchar(128) NOT NULL,
    format_version integer NOT NULL CHECK (format_version > 0),
    portable_secrets_version integer NOT NULL CHECK (portable_secrets_version >= 0),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 char(64) NOT NULL,
    recovery_key_sha256 char(64) NOT NULL,
    recovery_key_version integer NOT NULL CHECK (recovery_key_version = 2),
    wrapped_recovery_key bytea NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','AVAILABLE','EVICTED')),
    pinned_recovery_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz,
    evicted_at timestamptz,
    purged_at timestamptz,
    CHECK (pinned_recovery_id IS NULL OR state = 'AVAILABLE')
);
CREATE INDEX ix_saas_cloud_backup_available ON saas_cloud_backup
    (installation_id, captured_at DESC, sequence_number DESC, backup_id DESC)
    WHERE state = 'AVAILABLE';
