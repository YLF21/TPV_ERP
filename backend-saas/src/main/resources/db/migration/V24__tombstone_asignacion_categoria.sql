ALTER TABLE saas_member_category_bootstrap_assignment
    ALTER COLUMN category_id DROP NOT NULL;
ALTER TABLE saas_member_category_bootstrap_assignment
    ADD COLUMN assignment_action VARCHAR(8) NOT NULL DEFAULT 'SET';
ALTER TABLE saas_member_category_bootstrap_assignment
    ADD COLUMN lock_known BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE saas_member_category_bootstrap_assignment
    ALTER COLUMN assignment_action DROP DEFAULT;
ALTER TABLE saas_member_category_bootstrap_assignment
    ALTER COLUMN lock_known DROP DEFAULT;
ALTER TABLE saas_member_category_bootstrap_assignment
    ADD CONSTRAINT ck_saas_member_category_bootstrap_assignment_action CHECK (
        (assignment_action = 'SET' AND category_id IS NOT NULL)
        OR
        (assignment_action = 'CLEAR' AND category_id IS NULL
            AND lock_automatic = FALSE)
    );

ALTER TABLE saas_member_category_assignment
    ALTER COLUMN category_id DROP NOT NULL;
ALTER TABLE saas_member_category_assignment
    ADD COLUMN assignment_action VARCHAR(8) NOT NULL DEFAULT 'SET';
ALTER TABLE saas_member_category_assignment
    ADD COLUMN lock_known BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE saas_member_category_assignment
    ALTER COLUMN assignment_action DROP DEFAULT;
ALTER TABLE saas_member_category_assignment
    ALTER COLUMN lock_known DROP DEFAULT;
ALTER TABLE saas_member_category_assignment
    ADD CONSTRAINT ck_saas_member_category_assignment_action CHECK (
        (assignment_action = 'SET' AND category_id IS NOT NULL)
        OR
        (assignment_action = 'CLEAR' AND category_id IS NULL
            AND lock_automatic = FALSE)
    );
