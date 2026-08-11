create table logo_documento_tienda (
    id uuid primary key,
    tienda_id uuid not null references tienda(id) on delete cascade,
    mime_type varchar(16) not null,
    contenido bytea not null,
    sha256 varchar(64) not null,
    creado_en timestamp with time zone not null,
    constraint ck_logo_documento_tienda_mime
        check (mime_type in ('image/png', 'image/jpeg')),
    constraint ck_logo_documento_tienda_sha256
        check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_logo_documento_tienda_contenido
        check (octet_length(contenido) between 1 and 2097152),
    constraint uk_logo_documento_tienda_id_tienda unique (id, tienda_id),
    constraint uk_logo_documento_tienda_sha256 unique (tienda_id, sha256)
);

create index idx_logo_documento_tienda_tienda_creado
    on logo_documento_tienda (tienda_id, creado_en desc);

create table configuracion_documento_impreso_tienda (
    tienda_id uuid primary key references tienda(id) on delete cascade,
    logo_id uuid,
    observaciones_ticket varchar(2000),
    observaciones_factura varchar(2000),
    observaciones_albaran varchar(2000),
    version bigint not null default 0,
    constraint fk_configuracion_documento_impreso_logo
        foreign key (logo_id, tienda_id)
        references logo_documento_tienda(id, tienda_id) on delete restrict
);

insert into configuracion_documento_impreso_tienda (
    tienda_id,
    observaciones_factura,
    observaciones_albaran
)
select t.id, c.observaciones, c.observaciones
from tienda t
join configuracion_impresion_factura c on c.empresa_id = t.empresa_id
where c.observaciones is not null;
