-- AEAT pacing is scoped by the immutable fiscal identity, not by a JVM node.
create table flujo_envio_fiscal_scope (
    id uuid primary key,
    empresa_id uuid not null references empresa(id) on delete cascade,
    instalacion_id uuid not null references instalacion(id) on delete cascade,
    entorno varchar(16) not null,
    ultimo_envio_en timestamptz,
    siguiente_envio_en timestamptz,
    espera_recibida_segundos integer,
    lease_owner uuid,
    lease_hasta timestamptz,
    version bigint not null default 0,
    constraint uq_flujo_envio_fiscal_scope unique (empresa_id, instalacion_id, entorno),
    constraint ck_flujo_envio_fiscal_scope_entorno check (entorno in ('TEST', 'PRODUCTION')),
    constraint ck_flujo_envio_fiscal_scope_espera check (
        espera_recibida_segundos is null or espera_recibida_segundos between 0 and 9999),
    constraint ck_flujo_envio_fiscal_scope_lease check (
        (lease_owner is null and lease_hasta is null)
        or (lease_owner is not null and lease_hasta is not null))
);

create index ix_flujo_envio_fiscal_scope_lease
    on flujo_envio_fiscal_scope(empresa_id, instalacion_id, entorno, lease_hasta);

create index ix_estado_envio_fiscal_scope_claim
    on estado_envio_fiscal(estado, proximo_intento_en, registro_id);
