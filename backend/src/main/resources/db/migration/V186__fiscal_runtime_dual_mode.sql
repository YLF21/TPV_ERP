alter table configuracion_verifactu
    add column modo_actual varchar(16) not null default 'PRE_SIF',
    add column modo_desde timestamptz,
    add column verifactu_bloqueado_hasta date,
    add column modo_version bigint not null default 0;

update configuracion_verifactu
   set modo_actual = 'VERIFACTU',
       modo_desde = coalesce(activada_en, current_timestamp)
 where activacion_voluntaria = true
    or primera_remision_en is not null;

alter table configuracion_verifactu
    add constraint ck_config_verifactu_modo
    check (modo_actual in ('PRE_SIF', 'NO_VERIFACTU', 'VERIFACTU'));

alter table registro_fiscal
    add column modo_fiscal varchar(16) not null default 'VERIFACTU';

alter table registro_fiscal
    add constraint ck_registro_fiscal_modo
    check (modo_fiscal in ('NO_VERIFACTU', 'VERIFACTU'));

create table fiscal_runtime_guard (
    id smallint primary key,
    runtime_class varchar(16) not null,
    generation uuid not null,
    created_at timestamptz not null,
    version bigint not null default 0,
    check (id = 1),
    check (runtime_class in ('SANDBOX', 'REAL'))
);

insert into fiscal_runtime_guard (id, runtime_class, generation, created_at)
values (1, 'REAL', gen_random_uuid(), current_timestamp);

create table version_sistema_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    productor_nif varchar(32) not null,
    productor_nombre varchar(250) not null,
    nombre_sistema varchar(250) not null,
    id_sistema varchar(100) not null,
    version_sistema varchar(100) not null,
    numero_instalacion varchar(100) not null,
    declaracion_hash varchar(64),
    sandbox boolean not null default false,
    creado_en timestamptz not null,
    unique (empresa_id, instalacion_id, version_sistema, numero_instalacion)
);

create table transicion_modo_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    modo_anterior varchar(16) not null,
    modo_nuevo varchar(16) not null,
    solicitada_en timestamptz not null,
    efectiva_en timestamptz not null,
    causa varchar(32) not null,
    motivo text not null,
    expected_version bigint not null,
    unique (empresa_id, instalacion_id, efectiva_en),
    check (modo_anterior in ('PRE_SIF', 'NO_VERIFACTU', 'VERIFACTU')),
    check (modo_nuevo in ('PRE_SIF', 'NO_VERIFACTU', 'VERIFACTU')),
    check (modo_anterior <> modo_nuevo),
    check (char_length(trim(motivo)) > 0)
);

create table artefacto_registro_fiscal (
    registro_id uuid primary key references registro_fiscal(id),
    modo_fiscal varchar(16) not null,
    entorno varchar(16) not null,
    sandbox boolean not null default false,
    version_sistema_id uuid references version_sistema_fiscal(id),
    xml_sin_firmar text not null,
    xml_firmado text,
    xml_hash varchar(64) not null,
    certificado_huella varchar(128),
    qr_url text not null,
    qr_hash varchar(64) not null,
    qr_prefijo varchar(64) not null default 'QR tributario:',
    qr_leyenda text,
    aviso_pruebas text,
    creado_en timestamptz not null,
    check (modo_fiscal in ('NO_VERIFACTU', 'VERIFACTU')),
    check (entorno in ('TEST', 'PRODUCTION')),
    check (char_length(trim(xml_sin_firmar)) > 0),
    check (xml_firmado is null or char_length(trim(xml_firmado)) > 0),
    check (xml_hash ~ '^[0-9A-F]{64}$'),
    check (qr_hash ~ '^[0-9A-F]{64}$'),
    check (modo_fiscal = 'NO_VERIFACTU' or xml_firmado is null),
    check (modo_fiscal = 'VERIFACTU' or xml_firmado is not null),
    check (modo_fiscal = 'VERIFACTU' or qr_leyenda is null)
);

create index ix_artefacto_fiscal_qr_hash
    on artefacto_registro_fiscal(qr_hash);

create table snapshot_impresion_fiscal (
    registro_id uuid primary key references registro_fiscal(id),
    modo_fiscal varchar(16) not null,
    entorno varchar(16) not null,
    version_formato varchar(16) not null,
    generador_version varchar(64) not null,
    qr_url text not null,
    qr_hash varchar(64) not null,
    qr_prefijo varchar(64) not null default 'QR tributario:',
    qr_leyenda text,
    aviso_pruebas text,
    creado_en timestamptz not null,
    check (modo_fiscal in ('NO_VERIFACTU', 'VERIFACTU')),
    check (entorno in ('TEST', 'PRODUCTION')),
    check (qr_hash ~ '^[0-9A-F]{64}$'),
    check (modo_fiscal = 'VERIFACTU' or qr_leyenda is null)
);

create trigger tr_snapshot_impresion_fiscal_inmutable
before update or delete on snapshot_impresion_fiscal
for each row execute function impedir_mutacion_fiscal();

create table inventario_legacy_fiscal (
    registro_id uuid primary key references registro_fiscal(id),
    estado varchar(32) not null default 'LEGACY_UNFROZEN',
    detectado_en timestamptz not null,
    check (estado = 'LEGACY_UNFROZEN')
);

insert into inventario_legacy_fiscal (registro_id, detectado_en)
select r.id, current_timestamp
from registro_fiscal r
where not exists (
    select 1 from artefacto_registro_fiscal a where a.registro_id = r.id
);

create trigger tr_artefacto_fiscal_inmutable
before update or delete on artefacto_registro_fiscal
for each row execute function impedir_mutacion_fiscal();

create trigger tr_transicion_modo_fiscal_inmutable
before update or delete on transicion_modo_fiscal
for each row execute function impedir_mutacion_fiscal();
