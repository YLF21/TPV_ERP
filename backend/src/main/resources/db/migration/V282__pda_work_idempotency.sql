ALTER TABLE pda_trabajo_operativo
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS ux_pda_trabajo_store_client_request
    ON pda_trabajo_operativo (tienda_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

ALTER TABLE sesion
    ADD COLUMN IF NOT EXISTS ultima_actividad_en TIMESTAMPTZ;

UPDATE sesion
SET ultima_actividad_en = creada_en
WHERE ultima_actividad_en IS NULL;

ALTER TABLE sesion
    ALTER COLUMN ultima_actividad_en SET NOT NULL;

ALTER TABLE pda_lote_stock
    ADD COLUMN IF NOT EXISTS recibido_por UUID,
    ADD COLUMN IF NOT EXISTS ultimo_consumo_por UUID,
    ADD COLUMN IF NOT EXISTS ultimo_consumo_en TIMESTAMPTZ;
