-- El orden de las líneas forma parte del snapshot inmutable de impresión de almacén.
-- Los registros anteriores se numeran de forma determinista antes de exigir la regla.
alter table entrada_almacen_linea add column if not exists posicion integer;
with ordered as (
    select id, row_number() over (partition by entrada_id order by id) as position
    from entrada_almacen_linea
)
update entrada_almacen_linea line set posicion = ordered.position
from ordered where line.id = ordered.id and line.posicion is null;
alter table entrada_almacen_linea alter column posicion set default 1;
alter table entrada_almacen_linea alter column posicion set not null;
alter table entrada_almacen_linea drop constraint if exists entrada_almacen_linea_posicion_ck;
alter table entrada_almacen_linea add constraint entrada_almacen_linea_posicion_ck check (posicion > 0);
create unique index if not exists ux_entrada_almacen_linea_posicion
    on entrada_almacen_linea (entrada_id, posicion);

alter table salida_almacen_linea add column if not exists posicion integer;
with ordered as (
    select id, row_number() over (partition by salida_id order by id) as position
    from salida_almacen_linea
)
update salida_almacen_linea line set posicion = ordered.position
from ordered where line.id = ordered.id and line.posicion is null;
alter table salida_almacen_linea alter column posicion set default 1;
alter table salida_almacen_linea alter column posicion set not null;
alter table salida_almacen_linea drop constraint if exists salida_almacen_linea_posicion_ck;
alter table salida_almacen_linea add constraint salida_almacen_linea_posicion_ck check (posicion > 0);
create unique index if not exists ux_salida_almacen_linea_posicion
    on salida_almacen_linea (salida_id, posicion);
