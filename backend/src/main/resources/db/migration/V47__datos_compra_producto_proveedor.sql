alter table producto_proveedor
    add column ultimo_proveedor boolean not null default false,
    add column precio_compra_bruto numeric(19,2),
    add column descuento_compra numeric(5,2);

alter table producto_proveedor
    rename column ultima_fecha_entrada to ultima_entrada_en;

alter table producto_proveedor
    alter column ultima_entrada_en type timestamptz
    using ultima_entrada_en::timestamp at time zone 'UTC';

alter table producto_proveedor
    add column precio_compra_neto numeric(19,2)
    generated always as (
        case
            when precio_compra_bruto is null then null
            else round(precio_compra_bruto * (1 - coalesce(descuento_compra, 0) / 100), 2)
        end
    ) stored,
    add constraint ck_producto_proveedor_precio_compra_bruto
        check (precio_compra_bruto is null or precio_compra_bruto >= 0),
    add constraint ck_producto_proveedor_descuento_compra
        check (descuento_compra is null or descuento_compra between 0 and 100),
    add constraint ck_producto_proveedor_precio_compra_neto
        check (precio_compra_neto is null or precio_compra_neto >= 0);

with ranked as (
    select id,
           row_number() over (
               partition by producto_id
               order by ultima_entrada_en desc nulls last, id desc
           ) as position
    from producto_proveedor
    where ultima_entrada_en is not null
)
update producto_proveedor link
set ultimo_proveedor = true
from ranked
where ranked.id = link.id
  and ranked.position = 1;

create unique index ux_producto_proveedor_ultimo
    on producto_proveedor(producto_id)
    where ultimo_proveedor;
