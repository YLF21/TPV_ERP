create table cadena_eventos_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    ultima_secuencia bigint not null default 0,
    ultima_huella varchar(64),
    actualizada_en timestamptz not null,
    version bigint not null default 0,
    unique (empresa_id, instalacion_id),
    check (ultima_secuencia >= 0),
    check (ultima_huella is null or ultima_huella ~ '^[0-9A-F]{64}$')
);

create table registro_evento_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    secuencia bigint not null,
    tipo_evento varchar(2) not null,
    modo_fiscal varchar(16) not null,
    generado_en timestamptz not null,
    huella_evento_anterior varchar(64),
    huella_evento varchar(64) not null,
    xml_sin_firmar text not null,
    xml_firmado text not null,
    xml_hash varchar(64) not null,
    creado_en timestamptz not null,
    unique (empresa_id, instalacion_id, secuencia),
    check (secuencia > 0),
    check (tipo_evento in ('01','02','03','04','05','06','07','08','09','10','90')),
    check (modo_fiscal = 'NO_VERIFACTU'),
    check (huella_evento ~ '^[0-9A-F]{64}$'),
    check (huella_evento_anterior is null or huella_evento_anterior ~ '^[0-9A-F]{64}$'),
    check (char_length(trim(xml_sin_firmar)) > 0),
    check (char_length(trim(xml_firmado)) > 0),
    check (xml_hash ~ '^[0-9A-F]{64}$')
);

create index ix_registro_evento_fiscal_tenant_time
    on registro_evento_fiscal(empresa_id, instalacion_id, generado_en desc);

create trigger tr_registro_evento_fiscal_inmutable
before update or delete on registro_evento_fiscal
for each row execute function impedir_mutacion_fiscal();
