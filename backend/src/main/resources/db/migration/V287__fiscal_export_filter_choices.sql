ALTER TABLE trabajo_exportacion_fiscal
    ADD COLUMN opciones_filtro jsonb NOT NULL DEFAULT '{}'::jsonb;
