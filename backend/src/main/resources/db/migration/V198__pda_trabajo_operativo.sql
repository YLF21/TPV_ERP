create table pda_trabajo_operativo (
    id uuid primary key,
    tienda_id uuid not null,
    tipo varchar(20) not null,
    estado varchar(16) not null,
    titulo varchar(180) not null,
    referencia varchar(120),
    producto_codigo varchar(120),
    almacen_id uuid,
    cantidad numeric(19,3),
    numero_lote varchar(120),
    caduca_el date,
    ubicacion varchar(120),
    prioridad varchar(16) not null default 'NORMAL',
    notas text,
    evidencia_nombre varchar(240),
    evidencia_tipo varchar(120),
    evidencia_datos text,
    creado_por uuid not null,
    creado_en timestamptz not null,
    completado_por uuid,
    completado_en timestamptz,
    version bigint not null default 0,
    constraint ck_pda_trabajo_tipo check (tipo in ('INCIDENT','PICKING','LOT_CHECK','TASK')),
    constraint ck_pda_trabajo_estado check (estado in ('OPEN','DONE','CANCELLED')),
    constraint ck_pda_trabajo_cantidad check (cantidad is null or cantidad >= 0)
);
create index idx_pda_trabajo_tienda_fecha on pda_trabajo_operativo(tienda_id, creado_en desc);
create index idx_pda_trabajo_tienda_estado on pda_trabajo_operativo(tienda_id, estado, creado_en desc);