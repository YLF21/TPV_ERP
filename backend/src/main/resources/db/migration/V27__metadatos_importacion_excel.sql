create table producto_importacion_excel_linea (
    id uuid primary key,
    documento_id uuid not null references documento(id) on delete cascade,
    producto_id uuid not null references producto(id) on delete cascade,
    referencia_proveedor varchar(128),
    version bigint not null default 0,
    unique (documento_id, producto_id)
);

create index ix_producto_importacion_excel_linea_documento
    on producto_importacion_excel_linea(documento_id);
