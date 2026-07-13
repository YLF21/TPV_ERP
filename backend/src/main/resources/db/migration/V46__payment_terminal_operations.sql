ALTER TABLE pos_card_checkout ADD COLUMN IF NOT EXISTS requested_by VARCHAR(128);
ALTER TABLE pos_card_checkout ADD COLUMN IF NOT EXISTS requested_user_id UUID REFERENCES usuario(id);
ALTER TABLE pos_card_checkout ADD COLUMN IF NOT EXISTS requested_store_id UUID REFERENCES tienda(id);
ALTER TABLE pos_card_checkout ADD COLUMN IF NOT EXISTS requested_company_id UUID REFERENCES empresa(id);

CREATE TABLE payment_terminal_operation (
    id UUID PRIMARY KEY,
    terminal_id UUID NOT NULL REFERENCES terminal(id),
    store_id UUID NOT NULL REFERENCES tienda(id),
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('REDSYS_TPV_PC','PAYTEF','PAYCOMET','GLOBAL_PAYMENTS')),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('SIMULATED','LIVE','MANUAL')),
    operation_type VARCHAR(16) NOT NULL CHECK (operation_type IN ('CHARGE','VOID','REFUND')),
    original_operation_id UUID REFERENCES payment_terminal_operation(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR' CHECK (currency = 'EUR'),
    refunded_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'PENDING','SENT','APPROVED','DECLINED','CANCELLED','REFUNDED',
        'PARTIALLY_REFUNDED','TIMEOUT','ERROR','REVIEW_REQUIRED')),
    external_reference VARCHAR(128),
    authorization_code VARCHAR(64),
    configuration_hash VARCHAR(64),
    configuration_version BIGINT NOT NULL,
    document_id UUID REFERENCES documento(id),
    document_payment_id UUID REFERENCES documento_pago(id),
    processing_owner UUID,
    processing_lease_until TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    document_retry_count INTEGER NOT NULL DEFAULT 0 CHECK (document_retry_count >= 0),
    next_retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (terminal_id, idempotency_key),
    CONSTRAINT ck_payment_terminal_original_relation CHECK (
        (operation_type = 'CHARGE' AND original_operation_id IS NULL)
        OR (operation_type IN ('VOID','REFUND') AND original_operation_id IS NOT NULL)),
    CONSTRAINT ck_payment_terminal_refund_total CHECK (
        (status = 'REFUNDED' AND refunded_amount = amount)
        OR (status = 'PARTIALLY_REFUNDED' AND refunded_amount > 0 AND refunded_amount < amount)
        OR (status NOT IN ('REFUNDED','PARTIALLY_REFUNDED') AND refunded_amount = 0)),
    CONSTRAINT ck_payment_terminal_configuration_identity CHECK (
        (configuration_hash IS NULL AND configuration_version = -1)
        OR (configuration_hash ~ '^[0-9a-f]{64}$' AND configuration_version >= 0)),
    CONSTRAINT ck_payment_terminal_processing_lease CHECK (
        (processing_owner IS NULL) = (processing_lease_until IS NULL)),
    CONSTRAINT ck_payment_terminal_document_link CHECK (
        document_payment_id IS NULL OR document_id IS NOT NULL),
    CONSTRAINT ck_payment_terminal_timestamps CHECK (
        updated_at >= created_at AND (completed_at IS NULL OR completed_at >= created_at)),
    CONSTRAINT ck_payment_terminal_not_self_original CHECK (
        original_operation_id IS NULL OR original_operation_id <> id)
);

CREATE INDEX idx_payment_terminal_operation_recovery
    ON payment_terminal_operation(status, next_retry_at, processing_lease_until)
    WHERE status IN ('PENDING','TIMEOUT');
CREATE INDEX idx_payment_terminal_operation_original
    ON payment_terminal_operation(original_operation_id);

CREATE TABLE payment_terminal_event (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES payment_terminal_operation(id),
    previous_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    normalized_code VARCHAR(64),
    diagnostic VARCHAR(512),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_payment_terminal_event_statuses CHECK (
        (previous_status IS NULL OR previous_status IN (
            'PENDING','SENT','APPROVED','DECLINED','CANCELLED','REFUNDED',
            'PARTIALLY_REFUNDED','TIMEOUT','ERROR','REVIEW_REQUIRED'))
        AND new_status IN (
            'PENDING','SENT','APPROVED','DECLINED','CANCELLED','REFUNDED',
            'PARTIALLY_REFUNDED','TIMEOUT','ERROR','REVIEW_REQUIRED'))
);

CREATE INDEX idx_payment_terminal_event_operation_created
    ON payment_terminal_event(operation_id, created_at, id);

CREATE FUNCTION reject_payment_terminal_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment_terminal_event is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_payment_terminal_event_append_only
    BEFORE UPDATE OR DELETE ON payment_terminal_event
    FOR EACH ROW EXECUTE FUNCTION reject_payment_terminal_event_mutation();

INSERT INTO payment_terminal_operation (
    id, terminal_id, store_id, provider, mode, operation_type, original_operation_id,
    idempotency_key, request_hash, amount, currency, refunded_amount, status,
    external_reference, authorization_code, configuration_hash, configuration_version,
    document_id, processing_owner, processing_lease_until, retry_count,
    created_at, updated_at, completed_at, version)
SELECT checkout.id, checkout.terminal_id, terminal.tienda_id,
       'REDSYS_TPV_PC',
       CASE WHEN COALESCE(configuration.test_mode, true) THEN 'SIMULATED' ELSE 'LIVE' END,
       'CHARGE', NULL, checkout.id::text, checkout.request_hash, checkout.total, 'EUR', 0,
       checkout.status, checkout.reference, checkout.authorization_code,
       NULL, -1, checkout.documento_id,
       CASE WHEN checkout.gateway_owner IS NOT NULL AND checkout.gateway_lease_until IS NOT NULL
            THEN checkout.gateway_owner END,
       CASE WHEN checkout.gateway_owner IS NOT NULL AND checkout.gateway_lease_until IS NOT NULL
            THEN checkout.gateway_lease_until END,
       GREATEST(checkout.attempt - 1, 0),
       checkout.creado_en, checkout.actualizado_en, checkout.completado_en, checkout.version
FROM pos_card_checkout checkout
JOIN terminal ON terminal.id = checkout.terminal_id
LEFT JOIN configuracion_pago_terminal configuration ON configuration.terminal_id = checkout.terminal_id;

INSERT INTO payment_terminal_event (
    id, operation_id, previous_status, new_status, normalized_code, diagnostic, metadata, created_at)
SELECT gen_random_uuid(), checkout.id, NULL, checkout.status, 'MIGRATED_V45',
       checkout.message, '{"source":"pos_card_checkout"}'::jsonb, checkout.actualizado_en
FROM pos_card_checkout checkout;

ALTER TABLE pos_card_checkout
    ADD COLUMN payment_terminal_operation_id UUID UNIQUE REFERENCES payment_terminal_operation(id);

UPDATE pos_card_checkout
SET payment_terminal_operation_id = id;
