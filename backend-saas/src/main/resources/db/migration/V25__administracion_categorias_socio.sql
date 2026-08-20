CREATE TABLE saas_member_category_admin_audit (
    command_id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id),
    store_id UUID NOT NULL REFERENCES saas_store(id),
    actor_user_id UUID NOT NULL,
    actor_name VARCHAR(120) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    config_revision BIGINT,
    assignment_revision BIGINT,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_saas_member_category_admin_role CHECK (actor_role = 'ADMIN'),
    CONSTRAINT ck_saas_member_category_admin_operation CHECK (
        operation IN ('UPSERT_CATEGORY', 'SET_ASSIGNMENT', 'CLEAR_ASSIGNMENT')
    ),
    CONSTRAINT ck_saas_member_category_admin_revision CHECK (
        config_revision IS NOT NULL OR assignment_revision IS NOT NULL
    )
);

CREATE INDEX idx_saas_member_category_admin_audit_company
    ON saas_member_category_admin_audit (company_id, accepted_at DESC);

CREATE UNIQUE INDEX uq_saas_member_category_name
    ON saas_member_category (company_id, lower(name));
