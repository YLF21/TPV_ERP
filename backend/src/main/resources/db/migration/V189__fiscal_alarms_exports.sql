create table alarma_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    codigo varchar(64) not null,
    detalle text not null,
    detectada_en timestamptz not null,
    activa boolean not null default true,
    resuelta_en timestamptz,
    check (char_length(trim(detalle)) > 0),
    check ((activa and resuelta_en is null) or (not activa and resuelta_en is not null))
);

create index ix_alarma_fiscal_active
    on alarma_fiscal(empresa_id, instalacion_id, activa, detectada_en desc);

create table exportacion_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    tipo varchar(16) not null,
    evento_id uuid references registro_evento_fiscal(id),
    numero_registros bigint not null,
    contenido_hash varchar(64) not null,
    exportada_en timestamptz not null,
    check (tipo in ('BILLING', 'EVENTS')),
    check (numero_registros >= 0),
    check (contenido_hash ~ '^[0-9A-F]{64}$')
);

create table requerimiento_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    referencia varchar(100) not null,
    solicitado_en timestamptz not null,
    atendido_en timestamptz,
    estado varchar(16) not null default 'PENDIENTE',
    check (estado in ('PENDIENTE', 'EXPORTADO', 'ERROR')),
    unique (empresa_id, instalacion_id, referencia)
);
