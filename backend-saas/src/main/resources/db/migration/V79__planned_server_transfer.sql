ALTER TABLE saas_installation ADD COLUMN link_recovery_invalidated boolean NOT NULL DEFAULT false;
CREATE TABLE saas_server_transfer (
    id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES saas_installation(installation_id),
    state varchar(16) NOT NULL CHECK (state IN ('PREPARED','SEALED','ACTIVATED','CANCELLED')),
    destination_public_key text NOT NULL,
    destination_key_fingerprint char(64) NOT NULL,
    source_token_hash char(64) NOT NULL,
    backup_sha256 char(64),
    capsule_sha256 char(64),
    encrypted_credential text,
    activated_token_hash char(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    sealed_at timestamptz,
    activated_at timestamptz,
    cancelled_at timestamptz,
    CHECK ((state IN ('PREPARED','CANCELLED')) OR (backup_sha256 IS NOT NULL AND capsule_sha256 IS NOT NULL)),
    CHECK ((state <> 'ACTIVATED') OR (encrypted_credential IS NOT NULL AND activated_token_hash IS NOT NULL))
);
CREATE UNIQUE INDEX saas_server_transfer_one_pending ON saas_server_transfer(installation_id)
    WHERE state IN ('PREPARED','SEALED');
