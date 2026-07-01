create table sync_outbox (
    id uuid primary key,
    event_id uuid not null,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid references tienda(id),
    terminal_id uuid references terminal(id),
    tipo_entidad varchar(64) not null,
    entidad_id uuid not null,
    operacion varchar(32) not null,
    payload jsonb not null,
    creado_en timestamptz not null,
    estado varchar(16) not null default 'PENDIENTE',
    intentos integer not null default 0,
    ultimo_error text,
    enviado_en timestamptz,
    version bigint not null default 0,
    constraint ux_sync_outbox_event unique (event_id),
    check (operacion in ('CREAR', 'ACTUALIZAR', 'BORRAR', 'ANULAR', 'CONFIRMAR', 'CERRAR')),
    check (estado in ('PENDIENTE', 'ENVIANDO', 'ENVIADO', 'ERROR')),
    check (intentos >= 0)
);

create index ix_sync_outbox_empresa_estado_fecha
    on sync_outbox(empresa_id, estado, creado_en);

create index ix_sync_outbox_tienda_estado_fecha
    on sync_outbox(tienda_id, estado, creado_en);

create table sync_inbox (
    id uuid primary key,
    event_id uuid not null,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid references tienda(id),
    recibido_en timestamptz not null,
    procesado boolean not null default false,
    resultado varchar(16),
    error text,
    procesado_en timestamptz,
    version bigint not null default 0,
    constraint ux_sync_inbox_event unique (event_id),
    check (resultado is null or resultado in ('OK', 'DUPLICADO', 'ERROR'))
);

create index ix_sync_inbox_empresa_recibido
    on sync_inbox(empresa_id, recibido_en);

create index ix_sync_inbox_procesado
    on sync_inbox(procesado, recibido_en);
