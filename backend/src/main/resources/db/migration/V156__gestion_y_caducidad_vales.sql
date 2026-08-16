create table configuracion_vale_tienda (
    tienda_id uuid primary key references tienda(id) on delete cascade,
    vigencia_dias integer not null default 365,
    version bigint not null default 0,
    constraint configuracion_vale_tienda_vigencia_ck
        check (vigencia_dias between 1 and 36500)
);

insert into configuracion_vale_tienda (tienda_id, vigencia_dias)
select id, 365
from tienda
on conflict (tienda_id) do nothing;

alter table vale
    add column caduca_el date;

create index ix_vale_tienda_estado_caducidad
    on vale(tienda_id, status, caduca_el, creado_en desc);

create table vale_gestion_evento (
    id uuid primary key,
    vale_id uuid not null references vale(id) on delete restrict,
    tienda_id uuid not null references tienda(id) on delete restrict,
    tipo varchar(32) not null,
    usuario_id uuid not null references usuario(id) on delete restrict,
    terminal_id uuid references terminal(id) on delete restrict,
    ocurrido_en timestamptz not null,
    motivo varchar(500),
    detalle jsonb not null default '{}'::jsonb,
    constraint vale_gestion_evento_tipo_ck check (tipo in (
        'REACTIVATED', 'REPRINTED', 'REPRINT_FAILED'
    )),
    constraint vale_gestion_evento_motivo_ck check (
        tipo <> 'REACTIVATED' or nullif(trim(motivo), '') is not null
    )
);

create index ix_vale_gestion_evento_vale
    on vale_gestion_evento(vale_id, ocurrido_en desc);

create index ix_vale_gestion_evento_tienda
    on vale_gestion_evento(tienda_id, ocurrido_en desc);
