-- Immutable request/response evidence for AEAT batches. Existing attempt
-- payloads remain untouched; only new attempts may point at this evidence.
create table evidencia_envio_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    entorno varchar(16) not null,
    batch_owner uuid not null,
    creado_en timestamptz not null,
    request_preparado_en timestamptz not null,
    request_xml text not null,
    request_sha256 varchar(64) not null,
    constraint ck_evidencia_envio_fiscal_entorno
        check (entorno in ('TEST', 'PRODUCTION')),
    constraint ck_evidencia_envio_fiscal_request_size
        check (octet_length(request_xml) between 1 and 67108864),
    constraint ck_evidencia_envio_fiscal_request_hash
        check (request_sha256 ~ '^[0-9A-Fa-f]{64}$'),
    constraint fk_evidencia_envio_fiscal_scope
        foreign key (empresa_id, instalacion_id, entorno)
        references flujo_envio_fiscal_scope(empresa_id, instalacion_id, entorno),
    constraint uq_evidencia_envio_fiscal_scope_owner
        unique (empresa_id, instalacion_id, entorno, batch_owner)
);

create table respuesta_evidencia_envio_fiscal (
    id uuid primary key,
    evidencia_id uuid not null unique
        references evidencia_envio_fiscal(id),
    recibido_en timestamptz not null,
    response_payload text not null,
    response_sha256 varchar(64) not null,
    constraint ck_respuesta_evidencia_envio_fiscal_response_size
        check (octet_length(response_payload) between 0 and 10485760),
    constraint ck_respuesta_evidencia_envio_fiscal_response_hash
        check (response_sha256 ~ '^[0-9A-Fa-f]{64}$')
);

alter table intento_envio_fiscal
    add column if not exists evidencia_id uuid;

alter table intento_envio_fiscal
    add constraint fk_intento_envio_fiscal_evidencia
        foreign key (evidencia_id) references evidencia_envio_fiscal(id),
    add constraint ck_intento_envio_fiscal_evidencia_payload
        check (evidencia_id is null or (xml_enviado is null and respuesta is null));

create index ix_evidencia_envio_fiscal_scope_fecha
    on evidencia_envio_fiscal(empresa_id, instalacion_id, entorno, creado_en desc);

create index ix_intento_envio_fiscal_evidencia
    on intento_envio_fiscal(evidencia_id)
    where evidencia_id is not null;

-- Evidence is an audit record. A response is a separate append-only fact so
-- committing it never updates the exact request that was sent.
create or replace function impedir_mutacion_evidencia_envio_fiscal()
returns trigger
language plpgsql
as $$
begin
    raise exception 'La evidencia fiscal es inmutable';
end;
$$;

create trigger trg_evidencia_envio_fiscal_append_only
before update or delete on evidencia_envio_fiscal
for each row execute function impedir_mutacion_evidencia_envio_fiscal();

create trigger trg_respuesta_evidencia_envio_fiscal_append_only
before update or delete on respuesta_evidencia_envio_fiscal
for each row execute function impedir_mutacion_evidencia_envio_fiscal();
