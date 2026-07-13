CREATE TABLE sale_payment_session (
 id uuid PRIMARY KEY, store_id uuid NOT NULL REFERENCES tienda(id), terminal_id uuid NOT NULL REFERENCES terminal(id), user_id uuid NOT NULL REFERENCES usuario(id),
 request_hash varchar(64) NOT NULL, document_snapshot jsonb NOT NULL, total numeric(19,2) NOT NULL CHECK(total>0), currency varchar(3) NOT NULL DEFAULT 'EUR',
 status varchar(32) NOT NULL, ticket_id uuid REFERENCES documento(id), ticket_number varchar(32), compensation_note varchar(512), compensation_resolved_by uuid REFERENCES usuario(id), compensation_resolved_at timestamptz, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
 CONSTRAINT fk_sale_payment_terminal_store FOREIGN KEY (terminal_id, store_id) REFERENCES terminal(id, tienda_id)
);
CREATE INDEX idx_sale_payment_session_scope ON sale_payment_session(store_id,terminal_id,created_at DESC);
CREATE UNIQUE INDEX ux_sale_payment_session_active ON sale_payment_session(store_id,terminal_id,user_id) WHERE status IN ('COLLECTING','COVERED','COMPENSATION_REQUIRED');
CREATE TABLE sale_payment_allocation (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES sale_payment_session(id) ON DELETE CASCADE, idempotency_key varchar(128) NOT NULL,
 kind varchar(24) NOT NULL, amount numeric(19,2) NOT NULL CHECK(amount>0), provider varchar(32), mode varchar(16), operation_id uuid REFERENCES payment_terminal_operation(id),
 status varchar(16) NOT NULL, reference varchar(128), authorization_code varchar(64), message varchar(512), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
 CONSTRAINT uk_sale_payment_allocation_key UNIQUE(session_id,idempotency_key)
);
CREATE INDEX idx_sale_payment_allocation_operation ON sale_payment_allocation(operation_id);
