alter table control_alerta
    add column prioridad varchar(16) not null default 'MEDIUM',
    add column asignada_a uuid references usuario(id) on delete restrict,
    add column vence_en timestamptz;

alter table control_alerta
    add constraint control_alerta_prioridad_ck check (
        prioridad in ('INFORMATIONAL', 'MEDIUM', 'HIGH', 'CRITICAL')
    );

create index ix_control_alerta_tienda_prioridad_estado
    on control_alerta(tienda_id, prioridad, estado, creada_en desc);

create index ix_control_alerta_tienda_responsable_estado
    on control_alerta(tienda_id, asignada_a, estado, creada_en desc)
    where asignada_a is not null;

create index ix_control_alerta_tienda_vencimiento
    on control_alerta(tienda_id, vence_en, estado)
    where vence_en is not null and estado in ('NEW', 'REVIEWED');

create table control_alerta_trabajo_historial (
    id uuid primary key,
    alerta_id uuid not null references control_alerta(id) on delete restrict,
    tienda_id uuid not null references tienda(id) on delete restrict,
    prioridad_anterior varchar(16) not null,
    prioridad_nueva varchar(16) not null,
    responsable_anterior uuid references usuario(id) on delete restrict,
    responsable_nuevo uuid references usuario(id) on delete restrict,
    vence_en_anterior timestamptz,
    vence_en_nuevo timestamptz,
    comentario varchar(500),
    cambiado_por uuid not null references usuario(id) on delete restrict,
    cambiado_en timestamptz not null,
    constraint control_alerta_trabajo_prioridad_anterior_ck check (
        prioridad_anterior in ('INFORMATIONAL', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    constraint control_alerta_trabajo_prioridad_nueva_ck check (
        prioridad_nueva in ('INFORMATIONAL', 'MEDIUM', 'HIGH', 'CRITICAL')
    )
);

create index ix_control_alerta_trabajo_historial_alerta_fecha
    on control_alerta_trabajo_historial(alerta_id, cambiado_en, id);

create trigger trg_control_alerta_trabajo_historial_append_only
before update or delete on control_alerta_trabajo_historial
for each row execute function control_rechazar_mutacion();
