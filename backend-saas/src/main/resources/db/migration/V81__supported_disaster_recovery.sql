CREATE TABLE saas_disaster_recovery (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL REFERENCES saas_support_ticket(id),
    installation_id uuid NOT NULL REFERENCES saas_installation(installation_id),
    backup_id uuid NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('DOWNLOAD_AUTHORIZED','SUPPORT_REVIEW','ACTIVATION_APPROVED','ACTIVATED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0,
    destination_public_key text NOT NULL,
    destination_key_fingerprint char(64) NOT NULL,
    backup_sha256 char(64) NOT NULL,
    backup_descriptor jsonb NOT NULL,
    source_token_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(240) NOT NULL,
    note text NOT NULL,
    report jsonb,
    report_hash char(64),
    approved_checkpoint char(64),
    approved_by varchar(240),
    approved_at timestamptz,
    encrypted_credential text,
    activated_token_hash char(64),
    activated_at timestamptz,
    sequence_floor bigint NOT NULL DEFAULT 0,
    CHECK (state <> 'ACTIVATED' OR (encrypted_credential IS NOT NULL AND activated_token_hash IS NOT NULL))
);
CREATE UNIQUE INDEX saas_disaster_recovery_one_pending ON saas_disaster_recovery(installation_id)
    WHERE state IN ('DOWNLOAD_AUTHORIZED','SUPPORT_REVIEW','ACTIVATION_APPROVED');
