CREATE TABLE member_balance_reservation_local (
    id UUID PRIMARY KEY,
    central_reservation_id UUID NOT NULL UNIQUE,
    store_id UUID NOT NULL REFERENCES tienda(id),
    terminal_id UUID NOT NULL REFERENCES terminal(id),
    member_id UUID NOT NULL,
    sale_id VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reserved_total NUMERIC(19, 2) NOT NULL,
    prepared_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    prepare_operation_id UUID,
    ticket_id UUID,
    consumed_total NUMERIC(19, 2) NOT NULL DEFAULT 0,
    account_balance NUMERIC(19, 2) NOT NULL,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_member_balance_reservation_local_status
        CHECK (status IN ('ACTIVE', 'PREPARED', 'TICKET_COMMITTED', 'FINALIZE_PENDING',
            'ABORT_PENDING', 'RELEASE_PENDING', 'RELEASED', 'EXPIRED', 'CONSUMED')),
    CONSTRAINT ck_member_balance_reservation_local_amounts
        CHECK (reserved_total > 0
            AND consumed_total >= 0
            AND consumed_total <= reserved_total
            AND prepared_amount >= 0
            AND prepared_amount <= reserved_total
            AND account_balance >= 0)
);

CREATE INDEX idx_member_balance_reservation_local_sale
    ON member_balance_reservation_local(store_id, terminal_id, sale_id, created_at);

CREATE INDEX idx_member_balance_reservation_local_lease
    ON member_balance_reservation_local(status, lease_expires_at);

CREATE INDEX idx_member_balance_reservation_local_member
    ON member_balance_reservation_local(member_id, status);
