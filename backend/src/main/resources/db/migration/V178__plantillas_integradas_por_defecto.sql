insert into configuracion_origen_plantilla_documento (
    tienda_id,
    tipo,
    formato,
    origen
)
select tienda.id,
       modelo.tipo,
       modelo.formato,
       'INTEGRATED'
from tienda
cross join (values
    ('FACTURA_VENTA', 'A4'),
    ('TICKET', 'TICKET_80'),
    ('ALBARAN_VENTA', 'A4'),
    ('FACTURA_VENTA', 'TICKET_80'),
    ('VALE', 'TICKET_80')
) modelo(tipo, formato)
on conflict (tienda_id, tipo, formato) do update
set origen = excluded.origen,
    version = configuracion_origen_plantilla_documento.version + 1;
