insert into permiso (id, codigo, translation_key, grupo)
select gen_random_uuid(), source.codigo, source.translation_key, 'CONTROL'
from (values
    ('CONTROL_ALERTS_READ', 'control.permissions.alerts.read'),
    ('CONTROL_ALERTS_MANAGE', 'control.permissions.alerts.manage'),
    ('CONTROL_RULES_MANAGE', 'control.permissions.rules.manage')
) as source(codigo, translation_key)
where not exists (
    select 1 from permiso where permiso.codigo = source.codigo
);

create table control_regla (
    id uuid primary key,
    tienda_id uuid not null references tienda(id) on delete restrict,
    tipo varchar(48) not null,
    nombre varchar(160) not null,
    activa boolean not null default false,
    configuracion jsonb not null default '{}'::jsonb,
    numero_version integer not null,
    creado_por uuid not null references usuario(id) on delete restrict,
    actualizado_por uuid not null references usuario(id) on delete restrict,
    creado_en timestamptz not null,
    actualizado_en timestamptz not null,
    version bigint not null default 0,
    constraint control_regla_tipo_ck check (tipo in (
        'MANUAL_DISCOUNT_OVER_PERCENT',
        'INACTIVE_PRODUCT_SOLD',
        'TICKET_CANCELLED',
        'SALE_SCREEN_CLEARED'
    )),
    constraint control_regla_nombre_ck check (length(trim(nombre)) between 1 and 160),
    constraint control_regla_configuracion_ck check (jsonb_typeof(configuracion) = 'object'),
    constraint control_regla_numero_version_ck check (numero_version >= 1),
    constraint control_regla_fechas_ck check (actualizado_en >= creado_en)
);

create index ix_control_regla_tienda_tipo_activa
    on control_regla(tienda_id, tipo, activa);
create unique index ux_control_regla_tienda_nombre
    on control_regla(tienda_id, lower(nombre));

create table control_regla_version (
    id uuid primary key,
    regla_id uuid not null references control_regla(id) on delete restrict,
    tienda_id uuid not null references tienda(id) on delete restrict,
    numero_version integer not null,
    tipo varchar(48) not null,
    nombre varchar(160) not null,
    activa boolean not null,
    configuracion jsonb not null,
    cambiado_por uuid not null references usuario(id) on delete restrict,
    cambiado_en timestamptz not null,
    constraint control_regla_version_configuracion_ck
        check (jsonb_typeof(configuracion) = 'object'),
    constraint control_regla_version_uq unique (regla_id, numero_version)
);

create index ix_control_regla_version_tienda_regla
    on control_regla_version(tienda_id, regla_id, numero_version desc);

create table control_evento (
    id uuid primary key,
    tienda_id uuid not null references tienda(id) on delete restrict,
    regla_id uuid not null references control_regla(id) on delete restrict,
    regla_numero_version integer not null,
    regla_nombre varchar(160) not null,
    tipo varchar(48) not null,
    origen_tipo varchar(32) not null,
    origen_id uuid not null,
    documento_id uuid references documento(id) on delete restrict,
    documento_numero varchar(32),
    terminal_id uuid references terminal(id) on delete restrict,
    usuario_id uuid not null references usuario(id) on delete restrict,
    usuario_nombre varchar(128) not null,
    ocurrido_en timestamptz not null,
    datos jsonb not null,
    constraint control_evento_datos_ck check (jsonb_typeof(datos) = 'object'),
    constraint control_evento_origen_uq unique (regla_id, origen_tipo, origen_id)
);

create index ix_control_evento_tienda_fecha
    on control_evento(tienda_id, ocurrido_en desc);
create index ix_control_evento_documento
    on control_evento(tienda_id, documento_id)
    where documento_id is not null;

create table control_alerta (
    id uuid primary key,
    tienda_id uuid not null references tienda(id) on delete restrict,
    evento_id uuid not null references control_evento(id) on delete restrict,
    estado varchar(16) not null,
    creada_en timestamptz not null,
    actualizada_en timestamptz not null,
    version bigint not null default 0,
    constraint control_alerta_evento_uq unique (evento_id),
    constraint control_alerta_estado_ck check (estado in (
        'NEW', 'REVIEWED', 'CLOSED', 'DISMISSED'
    )),
    constraint control_alerta_fechas_ck check (actualizada_en >= creada_en)
);

create index ix_control_alerta_tienda_estado_fecha
    on control_alerta(tienda_id, estado, creada_en desc);

create table control_alerta_historial (
    id uuid primary key,
    alerta_id uuid not null references control_alerta(id) on delete restrict,
    tienda_id uuid not null references tienda(id) on delete restrict,
    estado_anterior varchar(16),
    estado_nuevo varchar(16) not null,
    comentario varchar(500),
    cambiado_por uuid not null references usuario(id) on delete restrict,
    cambiado_en timestamptz not null,
    constraint control_alerta_historial_estado_anterior_ck check (
        estado_anterior is null or estado_anterior in ('NEW', 'REVIEWED', 'CLOSED', 'DISMISSED')
    ),
    constraint control_alerta_historial_estado_nuevo_ck check (
        estado_nuevo in ('NEW', 'REVIEWED', 'CLOSED', 'DISMISSED')
    )
);

create index ix_control_alerta_historial_alerta_fecha
    on control_alerta_historial(alerta_id, cambiado_en, id);

create function control_rechazar_mutacion() returns trigger
language plpgsql as $$
begin
    raise exception 'Los registros de control son inmutables';
end;
$$;

create trigger trg_control_regla_no_delete
before delete on control_regla
for each row execute function control_rechazar_mutacion();

create trigger trg_control_regla_version_append_only
before update or delete on control_regla_version
for each row execute function control_rechazar_mutacion();

create trigger trg_control_evento_append_only
before update or delete on control_evento
for each row execute function control_rechazar_mutacion();

create trigger trg_control_alerta_no_delete
before delete on control_alerta
for each row execute function control_rechazar_mutacion();

create trigger trg_control_alerta_historial_append_only
before update or delete on control_alerta_historial
for each row execute function control_rechazar_mutacion();
