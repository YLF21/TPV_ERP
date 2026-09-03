-- Administrative safe-retirement foundations. Historical records are never
-- removed by this migration; it only adds representative lifecycle state,
-- bounded-list indexes and removes obsolete assignable delete permissions.

alter table comercial
    add column if not exists activo boolean not null default true;

create index if not exists ix_producto_management_page
    on producto (tienda_id, activo, (lower(nombre)), id);

create index if not exists ix_cliente_management_page
    on cliente (empresa_id, activo, (lower(nombre_fiscal)), id);

create index if not exists ix_proveedor_management_page
    on proveedor (empresa_id, activo, (lower(razon_social)), id);

create index if not exists ix_comercial_management_page
    on comercial (empresa_id, activo, (lower(nombre)), id);

create index if not exists ix_proveedor_comercial_comercial
    on proveedor_comercial (comercial_id, proveedor_id);

delete from rol_permiso
where permiso_id in (
    select id
    from permiso
    where codigo in ('PRODUCTS_DELETE', 'CUSTOMERS_DELETE', 'SUPPLIERS_DELETE')
);

delete from permiso
where codigo in ('PRODUCTS_DELETE', 'CUSTOMERS_DELETE', 'SUPPLIERS_DELETE');
