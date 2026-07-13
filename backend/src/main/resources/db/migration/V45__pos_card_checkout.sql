CREATE TABLE pos_card_checkout (
    id UUID PRIMARY KEY,
    terminal_id UUID NOT NULL REFERENCES terminal(id),
    schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    document_snapshot JSONB NOT NULL,
    total NUMERIC(19,2) NOT NULL CHECK (total > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','APPROVED','DECLINED','TIMEOUT','ERROR')),
    reference VARCHAR(128),
    authorization_code VARCHAR(64),
    message VARCHAR(512),
    documento_id UUID UNIQUE REFERENCES documento(id),
    ticket_number VARCHAR(32),
    gateway_owner UUID,
    gateway_lease_until TIMESTAMPTZ,
    ticket_owner UUID,
    ticket_lease_until TIMESTAMPTZ,
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt > 0),
    creado_en TIMESTAMPTZ NOT NULL,
    actualizado_en TIMESTAMPTZ NOT NULL,
    completado_en TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_pos_card_checkout_completion CHECK (
        (status = 'PENDING' AND completado_en IS NULL)
        OR (status <> 'PENDING' AND completado_en IS NOT NULL)
    ),
    CONSTRAINT ck_pos_card_checkout_document CHECK (
        documento_id IS NULL OR status = 'APPROVED'
    ),
    CONSTRAINT ck_pos_card_checkout_snapshot CHECK (
        document_snapshot->>'schemaVersion' = '1'
        AND document_snapshot ? 'ticket'
    ),
    CONSTRAINT ck_pos_card_checkout_ticket_number CHECK (
        (documento_id IS NULL AND ticket_number IS NULL)
        OR (documento_id IS NOT NULL AND ticket_number IS NOT NULL)
    )
);

CREATE INDEX idx_pos_card_checkout_terminal_created
    ON pos_card_checkout(terminal_id, creado_en DESC);
