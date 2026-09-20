-- Habilita el justificante de entrada de efectivo sin cambiar los modelos ya elegidos.
alter table plantilla_documento
    drop constraint ck_plantilla_documento_tipo_formato_v2;

alter table plantilla_documento
    add constraint ck_plantilla_documento_tipo_formato_v3 check (
        (tipo = 'FACTURA_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'ALBARAN_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'TICKET' and formato = 'TICKET_80')
        or (tipo = 'VALE' and formato = 'TICKET_80')
        or (tipo = 'TICKET_REGALO' and formato = 'TICKET_80')
        or (tipo = 'ENTRADA_CAJA' and formato = 'TICKET_80')
        or (tipo = 'RETIRADA_CAJA' and formato = 'TICKET_80')
        or (tipo = 'RECTIFICATIVA_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'SALIDA_ALMACEN' and formato = 'A4')
        or (tipo = 'ENTRADA_ALMACEN' and formato = 'A4')
        or (tipo = 'ALBARAN_ENTRADA' and formato = 'A4')
        or (tipo = 'FACTURA_ENTRADA' and formato = 'A4')
        or (tipo = 'HISTORIAL_VENTAS_PRODUCTO' and formato = 'A4')
    );

alter table configuracion_origen_plantilla_documento
    drop constraint ck_configuracion_origen_plantilla_tipo_formato_v2;

alter table configuracion_origen_plantilla_documento
    add constraint ck_configuracion_origen_plantilla_tipo_formato_v3 check (
        (tipo = 'FACTURA_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'ALBARAN_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'TICKET' and formato = 'TICKET_80')
        or (tipo = 'VALE' and formato = 'TICKET_80')
        or (tipo = 'TICKET_REGALO' and formato = 'TICKET_80')
        or (tipo = 'ENTRADA_CAJA' and formato = 'TICKET_80')
        or (tipo = 'RETIRADA_CAJA' and formato = 'TICKET_80')
        or (tipo = 'RECTIFICATIVA_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'SALIDA_ALMACEN' and formato = 'A4')
        or (tipo = 'ENTRADA_ALMACEN' and formato = 'A4')
        or (tipo = 'ALBARAN_ENTRADA' and formato = 'A4')
        or (tipo = 'FACTURA_ENTRADA' and formato = 'A4')
        or (tipo = 'HISTORIAL_VENTAS_PRODUCTO' and formato = 'A4')
    );

insert into configuracion_origen_plantilla_documento (tienda_id, tipo, formato, origen)
select id, 'ENTRADA_CAJA', 'TICKET_80', 'INTEGRATED'
from tienda
on conflict (tienda_id, tipo, formato) do nothing;
