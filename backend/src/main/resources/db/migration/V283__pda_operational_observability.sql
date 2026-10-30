CREATE TABLE pda_evento_operativo (
    id UUID PRIMARY KEY,
    tienda_id UUID NOT NULL REFERENCES tienda(id) ON DELETE CASCADE,
    terminal_id UUID NOT NULL REFERENCES terminal(id) ON DELETE CASCADE,
    client_event_id UUID NOT NULL,
    tipo VARCHAR(32) NOT NULL,
    codigo VARCHAR(64) NOT NULL,
    trabajo_id UUID NULL REFERENCES pda_trabajo_operativo(id) ON DELETE SET NULL,
    duracion_ms BIGINT NULL,
    ocurrido_en TIMESTAMPTZ NOT NULL,
    recibido_en TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_pda_evento_operativo_cliente UNIQUE (tienda_id, terminal_id, client_event_id),
    CONSTRAINT ck_pda_evento_operativo_tipo CHECK (tipo IN ('SYNC_FAILURE','SYNC_CONFLICT','TASK_BLOCKED','REQUEST_LATENCY','RECOVERED')),
    CONSTRAINT ck_pda_evento_operativo_duracion CHECK (duracion_ms IS NULL OR duracion_ms BETWEEN 0 AND 120000)
);

CREATE INDEX ix_pda_evento_operativo_tienda_fecha
    ON pda_evento_operativo (tienda_id, ocurrido_en DESC, id DESC);

CREATE INDEX ix_pda_evento_operativo_tienda_tipo_fecha
    ON pda_evento_operativo (tienda_id, tipo, ocurrido_en DESC);

CREATE INDEX ix_pda_evento_operativo_terminal_fecha
    ON pda_evento_operativo (tienda_id, terminal_id, ocurrido_en DESC);
