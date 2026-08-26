create table trabajo_exportacion_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid not null,
    instalacion_id uuid not null references instalacion(id),
    solicitado_por varchar(128) not null,
    tipo varchar(16) not null,
    scope varchar(16) not null,
    requerimiento_id uuid references requerimiento_fiscal(id),
    record_ids jsonb not null default '[]'::jsonb,
    fecha_inicio timestamptz,
    fecha_fin timestamptz,
    fecha_expedicion_desde date,
    fecha_expedicion_hasta date,
    numero_documento varchar(64),
    prefijo_documento varchar(64),
    operacion varchar(16),
    tipo_documento_fiscal varchar(4),
    modo_fiscal varchar(16),
    modo_ejecucion varchar(16) not null,
    secuencia_corte bigint not null,
    estado varchar(16) not null,
    procesados bigint not null default 0,
    hay_mas boolean not null default false,
    error text,
    ruta_fichero text,
    tamano_fichero bigint not null default 0,
    creado_en timestamptz not null,
    iniciado_en timestamptz,
    actualizado_en timestamptz not null,
    completado_en timestamptz,
    expira_en timestamptz not null,
    token_ejecucion uuid,
    version bigint not null default 0,
    check (tipo in ('BILLING', 'EVENTS')),
    check (scope in ('CURRENT', 'SELECTED', 'FILTERED', 'PERIOD')),
    check (tipo <> 'EVENTS' or scope = 'PERIOD'),
    check (
        (scope = 'CURRENT' and tipo = 'BILLING' and jsonb_array_length(record_ids) = 1
            and fecha_inicio is null and fecha_fin is null
            and fecha_expedicion_desde is null and fecha_expedicion_hasta is null
            and numero_documento is null and prefijo_documento is null
            and operacion is null and tipo_documento_fiscal is null and modo_fiscal is null)
        or (scope = 'SELECTED' and tipo = 'BILLING' and jsonb_array_length(record_ids) between 1 and 1000
            and fecha_inicio is null and fecha_fin is null
            and fecha_expedicion_desde is null and fecha_expedicion_hasta is null
            and numero_documento is null and prefijo_documento is null
            and operacion is null and tipo_documento_fiscal is null and modo_fiscal is null)
        or (scope = 'FILTERED' and tipo = 'BILLING' and jsonb_array_length(record_ids) = 0
            and (fecha_expedicion_desde is not null or fecha_expedicion_hasta is not null
                or numero_documento is not null or prefijo_documento is not null
                or operacion is not null or tipo_documento_fiscal is not null or modo_fiscal is not null))
        or (scope = 'PERIOD' and fecha_inicio is not null and fecha_fin is not null
            and jsonb_array_length(record_ids) = 0
            and fecha_expedicion_desde is null and fecha_expedicion_hasta is null
            and numero_documento is null and prefijo_documento is null
            and operacion is null and tipo_documento_fiscal is null and modo_fiscal is null)
    ),
    check (estado in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    check (modo_ejecucion in ('PRE_SIF', 'NO_VERIFACTU', 'VERIFACTU')),
    check (secuencia_corte >= 0),
    check (jsonb_typeof(record_ids) = 'array'),
    check (procesados >= 0),
    check (tamano_fichero >= 0),
    check (fecha_inicio is null or fecha_fin is null or fecha_fin >= fecha_inicio),
    check (fecha_expedicion_desde is null or fecha_expedicion_hasta is null
        or fecha_expedicion_hasta >= fecha_expedicion_desde),
    check (numero_documento is null or prefijo_documento is null),
    check (estado <> 'COMPLETED' or ruta_fichero is not null),
    check (estado <> 'FAILED' or error is not null),
    check ((estado = 'RUNNING' and token_ejecucion is not null)
        or (estado <> 'RUNNING' and token_ejecucion is null))
);

create unique index ux_trabajo_exportacion_fiscal_requerimiento_activo
    on trabajo_exportacion_fiscal(requerimiento_id)
    where requerimiento_id is not null and estado in ('QUEUED', 'RUNNING');

alter table trabajo_exportacion_fiscal
    add constraint fk_trabajo_exportacion_fiscal_tienda
    foreign key (tienda_id, empresa_id) references tienda(id, empresa_id);

create index ix_trabajo_exportacion_fiscal_scope
    on trabajo_exportacion_fiscal(empresa_id, tienda_id, instalacion_id, creado_en desc, id desc);

create index ix_trabajo_exportacion_fiscal_owner
    on trabajo_exportacion_fiscal(empresa_id, tienda_id, solicitado_por, creado_en desc);

create trigger tr_exportacion_fiscal_inmutable
before update or delete on exportacion_fiscal
for each row execute function impedir_mutacion_fiscal();
