alter table almacen add column display_order integer;

-- Preserve the initial presentation per store, including inactive warehouses.
-- GENERAL remains the default warehouse, but is not pinned after an explicit reorder.
with ordered as (
    select id, (row_number() over (
        partition by tienda_id order by predeterminado desc, nombre, id
    ) - 1)::integer as position
    from almacen
)
update almacen warehouse set display_order = ordered.position
from ordered where warehouse.id = ordered.id;

alter table almacen alter column display_order set default 0;
alter table almacen alter column display_order set not null;
alter table almacen add constraint ck_almacen_display_order check (display_order >= 0);
create index ix_almacen_tienda_display_order on almacen(tienda_id, display_order, id);
