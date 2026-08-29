alter table pda_trabajo_operativo drop constraint if exists ck_pda_trabajo_tipo;
alter table pda_trabajo_operativo drop constraint if exists ck_pda_trabajo_estado;
alter table pda_trabajo_operativo
    add constraint ck_pda_trabajo_tipo check (tipo in ('INCIDENT','PICKING','REPLENISHMENT','LOT_CHECK','TASK')),
    add constraint ck_pda_trabajo_estado check (estado in ('OPEN','PENDING','IN_PROGRESS','DONE','CANCELLED')),
    add column if not exists asignado_a uuid,
    add column if not exists vence_en timestamptz,
    add column if not exists ubicacion_origen varchar(120),
    add column if not exists ubicacion_destino varchar(120),
    add column if not exists ubicacion_validada_en timestamptz,
    add column if not exists ubicacion_validada_por uuid,
    add column if not exists ubicacion_origen_validada_en timestamptz,
    add column if not exists ubicacion_origen_validada_por uuid,
    add column if not exists ubicacion_destino_validada_en timestamptz,
    add column if not exists ubicacion_destino_validada_por uuid,
    add column if not exists comprobacion_id uuid,
    add column if not exists documento_id uuid,
    add column if not exists producto_id uuid,
    add column if not exists iniciado_en timestamptz,
    add column if not exists iniciado_por uuid;

create index if not exists idx_pda_trabajo_asignado_estado
    on pda_trabajo_operativo(tienda_id, asignado_a, estado, creado_en desc);
create index if not exists idx_pda_trabajo_historial
    on pda_trabajo_operativo(tienda_id, tipo, creado_en desc);
create index if not exists idx_pda_trabajo_comprobacion
    on pda_trabajo_operativo(comprobacion_id) where comprobacion_id is not null;

create table pda_trabajo_evidencia (
    id uuid primary key,
    trabajo_id uuid not null references pda_trabajo_operativo(id) on delete cascade,
    nombre varchar(240) not null,
    tipo_contenido varchar(120) not null,
    contenido bytea,
    referencia_almacenamiento varchar(1000),
    tamano bigint not null,
    creado_por uuid not null,
    creado_en timestamptz not null,
    version bigint not null default 0,
    constraint ck_pda_evidencia_fuente check (contenido is not null or referencia_almacenamiento is not null),
    constraint ck_pda_evidencia_tamano check (tamano >= 0 and tamano <= 10485760)
);
create index idx_pda_evidencia_trabajo on pda_trabajo_evidencia(trabajo_id, creado_en);

create table pda_ubicacion_almacen (
    id uuid primary key,
    tienda_id uuid not null,
    almacen_id uuid not null,
    codigo varchar(120) not null,
    descripcion varchar(240),
    activa boolean not null default true,
    version bigint not null default 0,
    constraint uq_pda_ubicacion unique(tienda_id, almacen_id, codigo)
);
create index idx_pda_ubicacion_almacen on pda_ubicacion_almacen(tienda_id, almacen_id, activa, codigo);

create table pda_lote_stock (
    id uuid primary key,
    tienda_id uuid not null,
    almacen_id uuid not null,
    producto_id uuid,
    producto_codigo varchar(120) not null,
    numero_lote varchar(120) not null,
    caduca_el date,
    proveedor_id uuid,
    referencia_proveedor varchar(120),
    cantidad numeric(19,3) not null,
    recibido_en timestamptz not null,
    agotado_en timestamptz,
    version bigint not null default 0,
    constraint uq_pda_lote unique(tienda_id, almacen_id, producto_codigo, numero_lote),
    constraint ck_pda_lote_cantidad check (cantidad >= 0)
);
create index idx_pda_lote_fefo
    on pda_lote_stock(tienda_id, almacen_id, producto_codigo, caduca_el asc nulls last, recibido_en)
    where cantidad > 0;
