-- Extiende el catálogo de combinaciones soportadas sin cambiar ninguna selección importada.
alter table plantilla_documento
    add constraint ck_plantilla_documento_tipo_formato_v2
        check (
            (tipo = 'FACTURA_VENTA' and formato in ('A4', 'TICKET_80'))
            or (tipo = 'ALBARAN_VENTA' and formato in ('A4', 'TICKET_80'))
            or (tipo = 'TICKET' and formato = 'TICKET_80')
            or (tipo = 'VALE' and formato = 'TICKET_80')
            or (tipo = 'TICKET_REGALO' and formato = 'TICKET_80')
            or (tipo = 'RETIRADA_CAJA' and formato = 'TICKET_80')
            or (tipo = 'RECTIFICATIVA_VENTA' and formato in ('A4', 'TICKET_80'))
            or (tipo = 'SALIDA_ALMACEN' and formato = 'A4')
            or (tipo = 'ENTRADA_ALMACEN' and formato = 'A4')
            or (tipo = 'ALBARAN_ENTRADA' and formato = 'A4')
            or (tipo = 'FACTURA_ENTRADA' and formato = 'A4')
            or (tipo = 'HISTORIAL_VENTAS_PRODUCTO' and formato = 'A4')
        );

alter table configuracion_origen_plantilla_documento
    drop constraint ck_configuracion_origen_plantilla_tipo_formato;

alter table configuracion_origen_plantilla_documento
    add constraint ck_configuracion_origen_plantilla_tipo_formato_v2 check (
        (tipo = 'FACTURA_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'ALBARAN_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'TICKET' and formato = 'TICKET_80')
        or (tipo = 'VALE' and formato = 'TICKET_80')
        or (tipo = 'TICKET_REGALO' and formato = 'TICKET_80')
        or (tipo = 'RETIRADA_CAJA' and formato = 'TICKET_80')
        or (tipo = 'RECTIFICATIVA_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'SALIDA_ALMACEN' and formato = 'A4')
        or (tipo = 'ENTRADA_ALMACEN' and formato = 'A4')
        or (tipo = 'ALBARAN_ENTRADA' and formato = 'A4')
        or (tipo = 'FACTURA_ENTRADA' and formato = 'A4')
        or (tipo = 'HISTORIAL_VENTAS_PRODUCTO' and formato = 'A4')
    );

insert into configuracion_origen_plantilla_documento (tienda_id, tipo, formato, origen)
select tienda.id, modelo.tipo, modelo.formato, 'INTEGRATED'
from tienda
cross join (values
    ('ALBARAN_VENTA', 'TICKET_80'),
    ('TICKET_REGALO', 'TICKET_80'),
    ('RETIRADA_CAJA', 'TICKET_80'),
    ('RECTIFICATIVA_VENTA', 'A4'),
    ('RECTIFICATIVA_VENTA', 'TICKET_80'),
    ('SALIDA_ALMACEN', 'A4'),
    ('ENTRADA_ALMACEN', 'A4'),
    ('ALBARAN_ENTRADA', 'A4'),
    ('FACTURA_ENTRADA', 'A4'),
    ('HISTORIAL_VENTAS_PRODUCTO', 'A4')
) modelo(tipo, formato)
on conflict (tienda_id, tipo, formato) do nothing;

-- Immutable line order is part of the warehouse print snapshot. Backfill old rows
-- deterministically before enforcing the invariant for new documents.
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
