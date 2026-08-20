ALTER TABLE saas_member_balance_account
    ADD COLUMN points_debt NUMERIC(19, 0) NOT NULL DEFAULT 0;

ALTER TABLE saas_member_balance_account
    ADD CONSTRAINT ck_saas_member_balance_account_points_debt_nonnegative
        CHECK (points_debt >= 0);

CREATE TABLE saas_member_points_authority (
    company_id UUID PRIMARY KEY REFERENCES saas_company(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_saas_member_points_authority_status
        CHECK (status IN ('NOT_INITIALIZED', 'ACTIVE')),
    CONSTRAINT ck_saas_member_points_authority_activation
        CHECK ((status = 'NOT_INITIALIZED' AND activated_at IS NULL)
            OR (status = 'ACTIVE' AND activated_at IS NOT NULL))
);

CREATE TABLE saas_member_points_operation (
    id BIGSERIAL PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    member_id UUID NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    amount NUMERIC(19, 0) NOT NULL,
    source_document_id UUID,
    original_document_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    local_points_delta NUMERIC(19, 0) NOT NULL,
    local_debt_delta NUMERIC(19, 0) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    actual_points_delta NUMERIC(19, 0),
    actual_debt_delta NUMERIC(19, 0),
    points_before NUMERIC(19, 0),
    points_after NUMERIC(19, 0),
    debt_before NUMERIC(19, 0),
    debt_after NUMERIC(19, 0),
    resolution_error VARCHAR(1000),
    received_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_saas_member_points_operation UNIQUE (company_id, operation_id),
    CONSTRAINT ck_saas_member_points_operation_type
        CHECK (operation_type IN ('SALE_EARN', 'RETURN_REVERSAL', 'SALE_CANCELLATION',
                                  'RETURN_CANCELLATION', 'MANUAL_ADJUSTMENT')),
    CONSTRAINT ck_saas_member_points_operation_amount
        CHECK ((operation_type = 'MANUAL_ADJUSTMENT' AND amount <> 0)
            OR (operation_type <> 'MANUAL_ADJUSTMENT' AND amount >= 0)),
    CONSTRAINT ck_saas_member_points_operation_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_saas_member_points_operation_status
        CHECK (status IN ('PENDING_BOOTSTRAP', 'PENDING_DEPENDENCY', 'APPLIED', 'CONFLICT')),
    CONSTRAINT ck_saas_member_points_operation_resolution
        CHECK ((status IN ('PENDING_BOOTSTRAP', 'PENDING_DEPENDENCY') AND resolved_at IS NULL)
            OR (status IN ('APPLIED', 'CONFLICT') AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_saas_member_points_operation_state_nonnegative
        CHECK ((points_before IS NULL OR points_before >= 0)
           AND (points_after IS NULL OR points_after >= 0)
           AND (debt_before IS NULL OR debt_before >= 0)
           AND (debt_after IS NULL OR debt_after >= 0))
);

CREATE INDEX ix_saas_member_points_operation_pending
    ON saas_member_points_operation(company_id, status, id);
CREATE INDEX ix_saas_member_points_operation_member
    ON saas_member_points_operation(company_id, member_id, id);
CREATE INDEX ix_saas_member_points_operation_source_document
    ON saas_member_points_operation(company_id, source_document_id)
    WHERE source_document_id IS NOT NULL;
CREATE INDEX ix_saas_member_points_operation_original_document
    ON saas_member_points_operation(company_id, original_document_id)
    WHERE original_document_id IS NOT NULL;

CREATE TABLE saas_member_points_debt_lot (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    origin_operation_id UUID NOT NULL,
    origin_document_id UUID,
    origin_type VARCHAR(40) NOT NULL,
    original_amount NUMERIC(19, 0) NOT NULL,
    remaining_amount NUMERIC(19, 0) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_saas_member_points_debt_origin UNIQUE (company_id, origin_operation_id),
    CONSTRAINT ck_saas_member_points_debt_origin_type
        CHECK (origin_type IN ('RETURN_REVERSAL', 'SALE_CANCELLATION')),
    CONSTRAINT ck_saas_member_points_debt_amounts
        CHECK (original_amount > 0 AND remaining_amount >= 0 AND remaining_amount <= original_amount),
    CONSTRAINT ck_saas_member_points_debt_status
        CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_saas_member_points_debt_cancelled
        CHECK ((status = 'ACTIVE' AND cancelled_at IS NULL)
            OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND remaining_amount = 0))
);

CREATE INDEX ix_saas_member_points_debt_fifo
    ON saas_member_points_debt_lot(company_id, member_id, created_sequence, id)
    WHERE status = 'ACTIVE' AND remaining_amount > 0;

CREATE TABLE saas_member_points_settlement (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    document_id UUID NOT NULL,
    original_document_id UUID,
    operation_id UUID NOT NULL,
    settlement_type VARCHAR(16) NOT NULL,
    amount NUMERIC(19, 0) NOT NULL,
    points_awarded NUMERIC(19, 0) NOT NULL,
    debt_settled NUMERIC(19, 0) NOT NULL,
    points_removed NUMERIC(19, 0) NOT NULL,
    debt_created NUMERIC(19, 0) NOT NULL,
    debt_lot_id UUID REFERENCES saas_member_points_debt_lot(id),
    cancelled_by_operation_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_saas_member_points_settlement_document
        UNIQUE (company_id, document_id, settlement_type),
    CONSTRAINT uq_saas_member_points_settlement_operation
        UNIQUE (company_id, operation_id),
    CONSTRAINT ck_saas_member_points_settlement_type
        CHECK (settlement_type IN ('SALE', 'RETURN')),
    CONSTRAINT ck_saas_member_points_settlement_amounts
        CHECK (amount >= 0 AND points_awarded >= 0 AND debt_settled >= 0
           AND points_removed >= 0 AND debt_created >= 0),
    CONSTRAINT ck_saas_member_points_settlement_shape
        CHECK ((settlement_type = 'SALE'
                AND points_removed = 0 AND debt_created = 0 AND debt_lot_id IS NULL
                AND points_awarded + debt_settled = amount)
            OR (settlement_type = 'RETURN'
                AND points_awarded = 0 AND debt_settled = 0
                AND points_removed + debt_created = amount
                AND ((debt_created = 0 AND debt_lot_id IS NULL)
                    OR (debt_created > 0 AND debt_lot_id IS NOT NULL)))),
    CONSTRAINT ck_saas_member_points_settlement_cancelled
        CHECK ((cancelled_by_operation_id IS NULL AND cancelled_at IS NULL)
            OR (cancelled_by_operation_id IS NOT NULL AND cancelled_at IS NOT NULL))
);

CREATE INDEX ix_saas_member_points_settlement_member
    ON saas_member_points_settlement(company_id, member_id, created_at);

CREATE TABLE saas_member_points_debt_allocation (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    sale_settlement_id UUID NOT NULL REFERENCES saas_member_points_settlement(id) ON DELETE RESTRICT,
    debt_lot_id UUID NOT NULL REFERENCES saas_member_points_debt_lot(id) ON DELETE RESTRICT,
    amount NUMERIC(19, 0) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    reversed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_saas_member_points_allocation UNIQUE (sale_settlement_id, debt_lot_id),
    CONSTRAINT ck_saas_member_points_allocation_amount CHECK (amount > 0),
    CONSTRAINT ck_saas_member_points_allocation_status
        CHECK (status IN ('APPLIED', 'REOPENED', 'CONVERTED_TO_POINTS_REVERSAL')),
    CONSTRAINT ck_saas_member_points_allocation_reversal
        CHECK ((status = 'APPLIED' AND reversed_at IS NULL)
            OR (status <> 'APPLIED' AND reversed_at IS NOT NULL))
);

CREATE INDEX ix_saas_member_points_allocation_lot
    ON saas_member_points_debt_allocation(debt_lot_id, status);
