CREATE TABLE payment_terminal_receipt (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE REFERENCES payment_terminal_operation(id),
    status VARCHAR(32) NOT NULL,
    normalized_code VARCHAR(64) NOT NULL,
    receipt_text VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE documento
    ADD COLUMN payment_terminal_refund_operation_id UUID UNIQUE
    REFERENCES payment_terminal_operation(id);

CREATE TABLE payment_terminal_reconciliation_batch (
    id UUID PRIMARY KEY,
    terminal_id UUID NOT NULL REFERENCES terminal(id),
    store_id UUID NOT NULL REFERENCES tienda(id),
    company_id UUID NOT NULL REFERENCES empresa(id),
    provider VARCHAR(32) NOT NULL,
    business_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    erp_total NUMERIC(19,2) NOT NULL DEFAULT 0,
    provider_total NUMERIC(19,2) NOT NULL DEFAULT 0,
    discrepancy NUMERIC(19,2) NOT NULL DEFAULT 0,
    normalized_code VARCHAR(64) NOT NULL,
    external_reference VARCHAR(128),
    diagnostic VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (terminal_id, business_date, provider)
);

CREATE INDEX idx_payment_terminal_reconciliation_scope
    ON payment_terminal_reconciliation_batch(company_id, store_id, id);

CREATE TABLE payment_terminal_reconciliation_event (
    id UUID PRIMARY KEY,
    reconciliation_id UUID NOT NULL REFERENCES payment_terminal_reconciliation_batch(id),
    status VARCHAR(32) NOT NULL,
    normalized_code VARCHAR(64) NOT NULL,
    diagnostic VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_payment_terminal_reconciliation_event
    ON payment_terminal_reconciliation_event(reconciliation_id, created_at, id);

CREATE FUNCTION reject_payment_terminal_reconciliation_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment_terminal_reconciliation_event is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_payment_terminal_reconciliation_event_append_only
    BEFORE UPDATE OR DELETE ON payment_terminal_reconciliation_event
    FOR EACH ROW EXECUTE FUNCTION reject_payment_terminal_reconciliation_event_mutation();
