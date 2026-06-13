create table instalacion (
    id uuid primary key,
    singleton_key boolean not null default true unique check (singleton_key),
    referencia varchar(32) not null unique,
    public_key text not null,
    creada_en timestamptz not null,
    demo_hasta timestamptz not null,
    version bigint not null default 0,
    check (char_length(trim(referencia)) between 1 and 32),
    check (char_length(trim(public_key)) > 0),
    check (demo_hasta = creada_en + interval '30 days')
);

create table empresa (
    id uuid primary key,
    tax_id varchar(64) not null,
    razon_social varchar(255) not null,
    domicilio_fiscal jsonb not null,
    version bigint not null default 0,
    check (char_length(trim(tax_id)) > 0),
    check (char_length(trim(razon_social)) > 0),
    check (jsonb_typeof(domicilio_fiscal) = 'object'),
    check (domicilio_fiscal ?& array['linea1', 'ciudad', 'codigoPostal', 'provincia', 'pais']),
    check (char_length(trim(domicilio_fiscal ->> 'linea1')) > 0),
    check (char_length(trim(domicilio_fiscal ->> 'ciudad')) > 0),
    check (char_length(trim(domicilio_fiscal ->> 'codigoPostal')) > 0),
    check (char_length(trim(domicilio_fiscal ->> 'provincia')) > 0),
    check (char_length(trim(domicilio_fiscal ->> 'pais')) > 0)
);

create table tienda (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    nombre varchar(255),
    direccion jsonb not null,
    address_normalized_hash varchar(128) not null,
    telefono varchar(64),
    email varchar(320),
    timezone varchar(64) not null,
    moneda char(3) not null,
    locale varchar(35) not null,
    version bigint not null default 0,
    unique (empresa_id, address_normalized_hash),
    check (nombre is null or char_length(trim(nombre)) > 0),
    check (jsonb_typeof(direccion) = 'object'),
    check (direccion ?& array['linea1', 'ciudad', 'codigoPostal', 'provincia', 'pais']),
    check (char_length(trim(direccion ->> 'linea1')) > 0),
    check (char_length(trim(direccion ->> 'ciudad')) > 0),
    check (char_length(trim(direccion ->> 'codigoPostal')) > 0),
    check (char_length(trim(direccion ->> 'provincia')) > 0),
    check (char_length(trim(direccion ->> 'pais')) > 0),
    check (char_length(trim(address_normalized_hash)) > 0),
    check (char_length(trim(timezone)) > 0),
    check (moneda = upper(moneda)),
    check (char_length(trim(locale)) > 0)
);

create table licencia (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    instalacion_id uuid not null references instalacion(id),
    referencia varchar(32) not null unique,
    valida_desde timestamptz not null,
    valida_hasta timestamptz not null,
    max_windows integer not null,
    max_pda integer not null,
    blob_original text not null,
    hash varchar(128) not null,
    format_version integer not null,
    importada_en timestamptz not null,
    import_metadata jsonb,
    import_result varchar(32) not null,
    import_reason text,
    activa boolean not null default false,
    version bigint not null default 0,
    check (valida_hasta > valida_desde),
    check (max_windows >= 1),
    check (max_pda >= 0),
    check (format_version >= 1),
    check (import_result in ('ACEPTADA', 'RECHAZADA')),
    check ((import_result = 'ACEPTADA' and import_reason is null)
        or (import_result = 'RECHAZADA' and char_length(trim(import_reason)) > 0))
);

create unique index ux_licencia_activa_tienda_instalacion
    on licencia(tienda_id, instalacion_id)
    where activa;

create table terminal (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    nombre varchar(128) not null,
    tipo varchar(32) not null,
    activa boolean not null default true,
    credential_hash varchar(255) not null,
    last_ip inet,
    last_seen_at timestamptz,
    version bigint not null default 0,
    check (char_length(trim(nombre)) > 0),
    check (tipo in ('SERVIDOR', 'TERMINAL_VENTA', 'PDA')),
    check (char_length(trim(credential_hash)) > 0)
);

create unique index ux_terminal_nombre_tienda_ci
    on terminal(tienda_id, lower(nombre));

create unique index ux_terminal_servidor_tienda
    on terminal(tienda_id)
    where tipo = 'SERVIDOR';

create table rol (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    nombre varchar(64) not null,
    protegido boolean not null default false,
    version bigint not null default 0,
    unique (tienda_id, nombre),
    check (nombre = upper(nombre)),
    check (char_length(trim(nombre)) > 0),
    check (nombre <> 'ADMIN' or protegido)
);

create table permiso (
    id uuid primary key,
    codigo varchar(128) not null unique,
    translation_key varchar(255) not null,
    grupo varchar(128) not null,
    version bigint not null default 0,
    check (codigo = upper(codigo)),
    check (char_length(trim(codigo)) > 0),
    check (char_length(trim(translation_key)) > 0),
    check (char_length(trim(grupo)) > 0)
);

create table rol_permiso (
    rol_id uuid not null references rol(id) on delete cascade,
    permiso_id uuid not null references permiso(id) on delete cascade,
    primary key (rol_id, permiso_id)
);

create table usuario (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    nombre varchar(64) not null,
    password_hash varchar(255) not null,
    rol_id uuid not null references rol(id),
    protegido boolean not null default false,
    activo boolean not null default true,
    last_login_at timestamptz,
    last_terminal_id uuid references terminal(id),
    version bigint not null default 0,
    unique (tienda_id, nombre),
    check (nombre = upper(nombre)),
    check (char_length(trim(nombre)) > 0),
    check (char_length(trim(password_hash)) > 0),
    check (nombre <> 'ADMIN' or protegido)
);

create table sesion (
    id uuid primary key,
    usuario_id uuid not null references usuario(id),
    terminal_id uuid references terminal(id),
    token_hash varchar(255) not null unique,
    creada_en timestamptz not null,
    revocada_en timestamptz,
    revocada_por_usuario_id uuid references usuario(id),
    revoke_reason text,
    version bigint not null default 0,
    check (char_length(trim(token_hash)) > 0),
    check ((revocada_en is null and revocada_por_usuario_id is null and revoke_reason is null)
        or (revocada_en is not null and char_length(trim(revoke_reason)) > 0))
);

create table auditoria (
    id uuid primary key,
    tienda_id uuid references tienda(id),
    usuario_id uuid references usuario(id),
    terminal_id uuid references terminal(id),
    event varchar(128) not null,
    result varchar(32) not null,
    datos jsonb,
    creada_en timestamptz not null,
    version bigint not null default 0,
    check (char_length(trim(event)) > 0),
    check (result in ('EXITO', 'FALLO'))
);

create table configuracion_backup (
    id uuid primary key,
    instalacion_id uuid not null unique references instalacion(id),
    hora time not null default time '12:00:00',
    daily_retention integer not null default 30,
    monthly_retention integer not null default 72,
    activa boolean not null default true,
    destino jsonb,
    version bigint not null default 0,
    check (daily_retention >= 30),
    check (monthly_retention >= 72)
);

create table ejecucion_backup (
    id uuid primary key,
    configuracion_id uuid not null references configuracion_backup(id),
    iniciada_en timestamptz not null,
    finalizada_en timestamptz,
    result varchar(32) not null,
    metadata jsonb,
    error_reason text,
    version bigint not null default 0,
    check (finalizada_en is null or finalizada_en >= iniciada_en),
    check (result in ('EN_CURSO', 'EXITO', 'FALLO')),
    check (result <> 'FALLO' or char_length(trim(error_reason)) > 0)
);

create index ix_tienda_empresa on tienda(empresa_id);
create index ix_licencia_tienda on licencia(tienda_id);
create index ix_terminal_tienda on terminal(tienda_id);
create index ix_usuario_tienda on usuario(tienda_id);
create index ix_sesion_usuario on sesion(usuario_id);
create index ix_auditoria_creada_en on auditoria(creada_en);
create index ix_ejecucion_backup_configuracion on ejecucion_backup(configuracion_id, iniciada_en desc);
