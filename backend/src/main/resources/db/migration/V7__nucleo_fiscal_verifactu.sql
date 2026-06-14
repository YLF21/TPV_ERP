alter table tienda
    add constraint uq_tienda_id_empresa unique (id, empresa_id);

alter table documento
    add constraint uq_documento_id_tienda unique (id, tienda_id);

create table configuracion_verifactu (
    id uuid primary key,
    empresa_id uuid not null unique references empresa(id),
    activacion_voluntaria boolean not null default false,
    activada_en timestamptz,
    primera_remision_en timestamptz,
    version bigint not null default 0,
    check (primera_remision_en is null or activada_en is not null),
    check (primera_remision_en is null or primera_remision_en >= activada_en)
);

create table cadena_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    ultimo_registro_id uuid,
    ultima_huella varchar(64),
    ultima_secuencia bigint not null default 0,
    actualizada_en timestamptz not null,
    version bigint not null default 0,
    unique (empresa_id, instalacion_id),
    unique (id, empresa_id, instalacion_id),
    check (ultima_secuencia >= 0),
    check (ultima_huella is null or ultima_huella ~ '^[0-9A-F]{64}$'),
    check (
        (ultima_secuencia = 0 and ultimo_registro_id is null and ultima_huella is null)
        or
        (ultima_secuencia > 0 and ultimo_registro_id is not null and ultima_huella is not null)
    )
);

create table registro_fiscal (
    id uuid primary key,
    cadena_id uuid not null,
    empresa_id uuid not null,
    instalacion_id uuid not null,
    tienda_id uuid not null,
    documento_id uuid,
    secuencia bigint not null,
    operacion varchar(16) not null,
    tipo_documento_fiscal varchar(4) not null,
    serie_numero varchar(64) not null,
    fecha_expedicion date not null,
    generado_en timestamptz not null,
    zona_horaria varchar(64) not null,
    nif_emisor varchar(9) not null,
    cuota_total numeric(19,2),
    importe_total numeric(19,2),
    huella_anterior varchar(64),
    huella varchar(64) not null,
    hash_snapshot varchar(64) not null,
    snapshot jsonb not null,
    version_formato varchar(16) not null,
    version_algoritmo varchar(16) not null,
    version_aplicacion varchar(32) not null,
    unique (cadena_id, secuencia),
    unique (cadena_id, huella),
    unique (cadena_id, id),
    unique (id, cadena_id),
    foreign key (cadena_id, empresa_id, instalacion_id)
        references cadena_fiscal(id, empresa_id, instalacion_id),
    foreign key (tienda_id, empresa_id)
        references tienda(id, empresa_id),
    foreign key (documento_id, tienda_id)
        references documento(id, tienda_id),
    check (secuencia > 0),
    check (operacion in ('ALTA', 'ANULACION')),
    check (tipo_documento_fiscal in ('F1', 'F2', 'F3', 'R1', 'R2', 'R3', 'R4', 'R5')),
    check (char_length(trim(serie_numero)) > 0),
    check (char_length(trim(zona_horaria)) > 0),
    check (nif_emisor ~ '^[0-9A-Z]{9}$'),
    check (
        (operacion = 'ALTA' and cuota_total is not null and importe_total is not null)
        or
        (operacion = 'ANULACION' and cuota_total is null and importe_total is null)
    ),
    check (
        (secuencia = 1 and huella_anterior is null)
        or
        (secuencia > 1 and huella_anterior is not null)
    ),
    check (huella ~ '^[0-9A-F]{64}$'),
    check (huella_anterior is null or huella_anterior ~ '^[0-9A-F]{64}$'),
    check (hash_snapshot ~ '^[0-9A-F]{64}$'),
    check (jsonb_typeof(snapshot) = 'object'),
    check (char_length(trim(version_formato)) > 0),
    check (char_length(trim(version_algoritmo)) > 0),
    check (char_length(trim(version_aplicacion)) > 0)
);

alter table cadena_fiscal
    add constraint fk_cadena_ultimo_registro
    foreign key (ultimo_registro_id, id)
    references registro_fiscal(id, cadena_id);

create table registro_fiscal_relacion (
    cadena_id uuid not null,
    registro_id uuid not null,
    relacionado_id uuid not null,
    tipo varchar(16) not null,
    primary key (cadena_id, registro_id, relacionado_id, tipo),
    foreign key (cadena_id, registro_id)
        references registro_fiscal(cadena_id, id),
    foreign key (cadena_id, relacionado_id)
        references registro_fiscal(cadena_id, id),
    check (registro_id <> relacionado_id),
    check (tipo in ('SUBSANA', 'ANULA', 'RECTIFICA', 'SUSTITUYE'))
);

create table estado_envio_fiscal (
    registro_id uuid primary key references registro_fiscal(id),
    estado varchar(24) not null default 'PENDIENTE',
    ultimo_error_codigo varchar(64),
    ultimo_error text,
    actualizado_en timestamptz not null,
    version bigint not null default 0,
    check (estado in (
        'PENDIENTE', 'ENVIANDO', 'ENVIADO', 'ACEPTADO',
        'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'DEFECTUOSO'
    ))
);

create index ix_registro_fiscal_documento
    on registro_fiscal(documento_id);

create index ix_registro_fiscal_empresa_fecha
    on registro_fiscal(empresa_id, generado_en desc);

create index ix_estado_envio_fiscal_estado
    on estado_envio_fiscal(estado, actualizado_en);

create function impedir_mutacion_fiscal() returns trigger
language plpgsql as $$
begin
    raise exception 'Los registros fiscales son inmutables'
        using errcode = 'P0001';
end;
$$;

create trigger tr_registro_fiscal_inmutable
before update or delete on registro_fiscal
for each row execute function impedir_mutacion_fiscal();

create trigger tr_relacion_fiscal_inmutable
before update or delete on registro_fiscal_relacion
for each row execute function impedir_mutacion_fiscal();
