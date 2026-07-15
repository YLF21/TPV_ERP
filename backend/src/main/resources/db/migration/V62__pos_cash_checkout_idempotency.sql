CREATE TABLE pos_cash_checkout (
    id UUID PRIMARY KEY,
    checkout_id UUID NOT NULL,
    company_id UUID NOT NULL REFERENCES empresa(id),
    store_id UUID NOT NULL REFERENCES tienda(id),
    terminal_id UUID NOT NULL REFERENCES terminal(id),
    user_id UUID NOT NULL REFERENCES usuario(id),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','COMPLETED')),
    documento_id UUID UNIQUE REFERENCES documento(id),
    ticket_number VARCHAR(32),
    total NUMERIC(19,2),
    received NUMERIC(19,2),
    change_amount NUMERIC(19,2),
    ticket_snapshot JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(company_id,store_id,terminal_id,user_id,checkout_id),
    CONSTRAINT ck_pos_cash_checkout_result CHECK (
        (status = 'PENDING' AND documento_id IS NULL AND ticket_number IS NULL
            AND total IS NULL AND received IS NULL AND change_amount IS NULL
            AND ticket_snapshot IS NULL)
        OR
        (status = 'COMPLETED' AND documento_id IS NOT NULL AND ticket_number IS NOT NULL
            AND total IS NOT NULL AND received IS NOT NULL AND change_amount IS NOT NULL
            AND ticket_snapshot IS NOT NULL)
    )
);

CREATE INDEX idx_pos_cash_checkout_scope_created
    ON pos_cash_checkout(company_id, store_id, terminal_id, user_id, created_at DESC);
