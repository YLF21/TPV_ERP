ALTER TABLE documento
    ADD COLUMN cuenta_cobrar boolean NOT NULL DEFAULT false;

CREATE INDEX idx_documento_ticket_cuenta_cobrar
    ON documento (tienda_id, cliente_id, estado, fecha_vencimiento)
    WHERE tipo = 'TICKET' AND cuenta_cobrar = true;
