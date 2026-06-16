create table intento_envio_fiscal (
    id uuid primary key,
    registro_id uuid not null references registro_fiscal(id),
    intentado_en timestamptz not null,
    estado varchar(24) not null,
    error_codigo varchar(64),
    error text,
    xml_enviado text,
    respuesta text,
    version bigint not null default 0,
    check (estado in (
        'PENDIENTE', 'ENVIANDO', 'ENVIADO', 'ACEPTADO',
        'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'DEFECTUOSO'
    )),
    check (estado not in ('ACEPTADO_CON_ERRORES', 'RECHAZADO', 'DEFECTUOSO')
        or (char_length(trim(error_codigo)) > 0 and char_length(trim(error)) > 0))
);

create index ix_intento_envio_fiscal_registro_fecha
    on intento_envio_fiscal(registro_id, intentado_en desc);
