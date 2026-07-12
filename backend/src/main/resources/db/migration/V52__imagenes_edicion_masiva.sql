create table producto_edicion_masiva_imagen (
    id uuid primary key,
    edicion_id uuid not null references producto_edicion_masiva(id) on delete cascade,
    producto_id uuid references producto(id) on delete set null,
    posicion integer not null,
    nombre_archivo varchar(255) not null,
    tipo_contenido varchar(100) not null,
    tamano bigint not null,
    sha256 varchar(64) not null,
    contenido bytea not null,
    constraint ck_producto_edicion_masiva_imagen_posicion check (posicion >= 0),
    constraint ck_producto_edicion_masiva_imagen_nombre check (btrim(nombre_archivo) <> ''),
    constraint ck_producto_edicion_masiva_imagen_tipo check (tipo_contenido like 'image/%'),
    constraint ck_producto_edicion_masiva_imagen_tamano check (
        tamano between 1 and 5242880
        and tamano = octet_length(contenido)
    ),
    constraint ck_producto_edicion_masiva_imagen_sha256 check (
        sha256 ~ '^[0-9a-f]{64}$'
    )
);

create index ix_producto_edicion_masiva_imagen_edicion_posicion
    on producto_edicion_masiva_imagen (edicion_id, posicion);

create index ix_producto_edicion_masiva_imagen_producto
    on producto_edicion_masiva_imagen (producto_id)
    where producto_id is not null;
