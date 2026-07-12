create table producto_edicion_masiva_secuencia (
    tienda_id uuid not null references tienda(id) on delete cascade,
    fecha date not null,
    ultimo_numero integer not null,
    primary key (tienda_id, fecha),
    constraint ck_producto_edicion_masiva_secuencia
        check (ultimo_numero between 1 and 999)
);

insert into producto_edicion_masiva_secuencia (tienda_id, fecha, ultimo_numero)
select tienda_id,
       to_date(left(codigo, 8), 'YYYYMMDD'),
       max(right(codigo, 3)::integer)
from producto_edicion_masiva
where codigo ~ '^[0-9]{11}$'
group by tienda_id, to_date(left(codigo, 8), 'YYYYMMDD');

alter table producto_edicion_masiva
    add constraint ck_producto_edicion_masiva_codigo_formato
    check (codigo ~ '^[0-9]{11}$');

alter table producto_proveedor
    add column principal boolean not null default false;

update producto_proveedor
set principal = true
where ultimo_proveedor = true;

create unique index ux_producto_proveedor_principal
    on producto_proveedor(producto_id)
    where principal;

alter table producto_proveedor
    drop constraint if exists ck_producto_proveedor_precio_compra_neto;

alter table producto_proveedor
    drop column if exists precio_compra_neto;
