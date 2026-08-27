create table trabajo_integridad_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid not null,
    instalacion_id uuid not null references instalacion(id),
    solicitado_por varchar(128) not null,
    modo_ejecucion varchar(16) not null,
    secuencia_facturacion_corte bigint not null default 0,
    secuencia_eventos_corte bigint not null default 0,
    estado varchar(16) not null,
    token_ejecucion uuid,
    facturacion_comprobada bigint not null default 0,
    eventos_comprobados bigint not null default 0,
    anomalias_total bigint not null default 0,
    anomalias_facturacion bigint not null default 0,
    anomalias_eventos bigint not null default 0,
    evidencia_codigos jsonb not null default '[]'::jsonb,
    error text,
    creado_en timestamptz not null,
    iniciado_en timestamptz,
    actualizado_en timestamptz not null,
    completado_en timestamptz,
    version bigint not null default 0,
    check (modo_ejecucion in ('PRE_SIF', 'NO_VERIFACTU', 'VERIFACTU')),
    check (estado in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    check (secuencia_facturacion_corte >= 0 and secuencia_eventos_corte >= 0),
    check (facturacion_comprobada >= 0 and eventos_comprobados >= 0),
    check (anomalias_total >= 0 and anomalias_facturacion >= 0 and anomalias_eventos >= 0),
    check (jsonb_typeof(evidencia_codigos) = 'array'),
    check (jsonb_array_length(evidencia_codigos) <= 1000),
    check (estado <> 'FAILED' or error is not null),
    check (estado <> 'COMPLETED' or (completado_en is not null and error is null)),
    check ((estado = 'RUNNING' and token_ejecucion is not null)
        or (estado <> 'RUNNING' and token_ejecucion is null))
);

alter table trabajo_integridad_fiscal
    add constraint fk_trabajo_integridad_fiscal_tienda
    foreign key (tienda_id, empresa_id) references tienda(id, empresa_id);

create index ix_trabajo_integridad_fiscal_scope
    on trabajo_integridad_fiscal(empresa_id, tienda_id, instalacion_id, creado_en desc, id desc);

create index ix_trabajo_integridad_fiscal_owner
    on trabajo_integridad_fiscal(empresa_id, tienda_id, solicitado_por, creado_en desc, id desc);

create unique index ux_trabajo_integridad_fiscal_activo
    on trabajo_integridad_fiscal(empresa_id, instalacion_id)
    where estado in ('QUEUED', 'RUNNING');
