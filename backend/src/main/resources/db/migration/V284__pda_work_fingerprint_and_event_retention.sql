ALTER TABLE pda_trabajo_operativo
    ADD COLUMN IF NOT EXISTS client_request_fingerprint VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_pda_evento_operativo_retencion
    ON pda_evento_operativo (recibido_en, id);
