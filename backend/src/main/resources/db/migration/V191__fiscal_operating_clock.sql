create table reloj_operativo_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    observado_en timestamptz not null,
    segundos_desde_resumen bigint not null default 0,
    version bigint not null default 0,
    unique (empresa_id, instalacion_id),
    check (segundos_desde_resumen >= 0)
);

create index ix_reloj_operativo_fiscal_tenant
    on reloj_operativo_fiscal(empresa_id, instalacion_id);
