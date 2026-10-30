-- V170 added CENTRAL_ACTIVE to the metadata check, but V169's separate status
-- constraint still rejects it. Keep the metadata, sequence and revision guards.
ALTER TABLE member_points_projection_state
    DROP CONSTRAINT ck_member_points_projection_status,
    ADD CONSTRAINT ck_member_points_projection_status CHECK (status IN (
        'LOCAL_ACTIVE', 'FROZEN', 'WAITING_OFFICIAL',
        'CATCHING_UP', 'CONFLICT', 'CENTRAL_ACTIVE'
    ));
