ALTER TABLE saas_member_points_operation
    ALTER COLUMN payload_hash TYPE VARCHAR(64);

ALTER TABLE saas_member_points_bootstrap_snapshot
    ALTER COLUMN snapshot_checksum TYPE VARCHAR(64);

ALTER TABLE saas_member_points_bootstrap_chunk
    ALTER COLUMN chunk_hash TYPE VARCHAR(64);

ALTER TABLE saas_member_points_bootstrap_staging_operation
    ALTER COLUMN contract_hash TYPE VARCHAR(64);

ALTER TABLE saas_member_points_bootstrap_absorbed_operation
    ALTER COLUMN contract_hash TYPE VARCHAR(64);

ALTER TABLE saas_member_points_opening
    ALTER COLUMN source_checksum TYPE VARCHAR(64);
