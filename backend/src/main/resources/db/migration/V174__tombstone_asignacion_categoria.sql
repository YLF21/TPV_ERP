ALTER TABLE member_movement
    ADD COLUMN category_assignment_action VARCHAR(8),
    ADD COLUMN category_lock_automatic BOOLEAN;

ALTER TABLE member_movement
    ADD CONSTRAINT ck_member_movement_category_assignment_action CHECK (
        category_assignment_action IS NULL
        OR category_assignment_action IN ('SET', 'CLEAR')
    );

ALTER TABLE member_category_bootstrap_assignment
    ALTER COLUMN category_id DROP NOT NULL,
    ADD COLUMN assignment_action VARCHAR(8) NOT NULL DEFAULT 'SET',
    ADD COLUMN lock_known BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE member_category_bootstrap_assignment
    ALTER COLUMN assignment_action DROP DEFAULT,
    ALTER COLUMN lock_known DROP DEFAULT,
    ADD CONSTRAINT ck_member_category_bootstrap_assignment_action CHECK (
        (assignment_action = 'SET' AND category_id IS NOT NULL)
        OR
        (assignment_action = 'CLEAR' AND category_id IS NULL
            AND lock_automatic = FALSE)
    );
