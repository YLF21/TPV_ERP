CREATE TABLE saas_member_loyalty_bootstrap (
    company_id UUID PRIMARY KEY REFERENCES saas_company(id),
    source_store_id UUID NOT NULL REFERENCES saas_store(id),
    source_installation_id UUID REFERENCES saas_installation(id),
    source_checksum VARCHAR(64),
    snapshot_at TIMESTAMP WITH TIME ZONE,
    designated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE saas_member_balance_account (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES saas_company(id),
    member_id UUID NOT NULL,
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    points NUMERIC(19, 4) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_saas_member_balance_account UNIQUE (company_id, member_id),
    CONSTRAINT ck_saas_member_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT ck_saas_member_points_non_negative CHECK (points >= 0)
);

CREATE INDEX idx_saas_member_balance_account_company
    ON saas_member_balance_account(company_id, member_id);

CREATE TABLE saas_member_balance_lot (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES saas_member_balance_account(id),
    original_amount NUMERIC(19, 2) NOT NULL,
    remaining_amount NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    source_movement_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_saas_member_balance_lot_original_positive CHECK (original_amount > 0),
    CONSTRAINT ck_saas_member_balance_lot_remaining_valid
        CHECK (remaining_amount >= 0 AND remaining_amount <= original_amount)
);

CREATE INDEX idx_saas_member_balance_lot_fifo
    ON saas_member_balance_lot(account_id, created_at, id);

CREATE INDEX idx_saas_member_balance_lot_expiration
    ON saas_member_balance_lot(expires_at);

CREATE TABLE saas_member_balance_reservation (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES saas_member_balance_account(id),
    store_id UUID NOT NULL REFERENCES saas_store(id),
    installation_id UUID NOT NULL REFERENCES saas_installation(id),
    terminal_id VARCHAR(120) NOT NULL,
    sale_id VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reserved_total NUMERIC(19, 2) NOT NULL,
    prepared_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    prepare_operation_id UUID,
    prepared_at TIMESTAMP WITH TIME ZONE,
    consumed_total NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_saas_member_balance_reservation_status
        CHECK (status IN ('ACTIVE', 'PREPARED', 'RELEASED', 'EXPIRED', 'CONSUMED')),
    CONSTRAINT ck_saas_member_balance_reservation_amounts
        CHECK (reserved_total > 0
            AND prepared_amount >= 0 AND prepared_amount <= reserved_total
            AND consumed_total >= 0 AND consumed_total <= reserved_total),
    CONSTRAINT ck_saas_member_balance_reservation_prepare
        CHECK ((status IN ('PREPARED', 'CONSUMED')
                AND prepare_operation_id IS NOT NULL AND prepared_amount > 0 AND prepared_at IS NOT NULL)
            OR status NOT IN ('PREPARED', 'CONSUMED'))
);

CREATE INDEX idx_saas_member_balance_reservation_active
    ON saas_member_balance_reservation(account_id, status, lease_expires_at);

CREATE INDEX idx_saas_member_balance_reservation_owner
    ON saas_member_balance_reservation(installation_id, terminal_id, sale_id);

CREATE TABLE saas_member_balance_reservation_lot (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES saas_member_balance_reservation(id),
    lot_id UUID NOT NULL REFERENCES saas_member_balance_lot(id),
    reserved_amount NUMERIC(19, 2) NOT NULL,
    consumed_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_saas_member_balance_reservation_lot UNIQUE (reservation_id, lot_id),
    CONSTRAINT ck_saas_member_balance_reservation_lot_amounts
        CHECK (reserved_amount > 0 AND consumed_amount >= 0 AND consumed_amount <= reserved_amount)
);

CREATE INDEX idx_saas_member_balance_reservation_lot_reservation
    ON saas_member_balance_reservation_lot(reservation_id);
