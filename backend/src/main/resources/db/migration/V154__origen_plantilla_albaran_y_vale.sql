insert into configuracion_origen_plantilla_documento (
    tienda_id, tipo, formato, origen
)
select tienda.id,
       modelo.tipo,
       modelo.formato,
       case when exists (
           select 1
           from plantilla_documento plantilla
           where plantilla.tipo = modelo.tipo
             and plantilla.formato = modelo.formato
             and plantilla.estado = 'ACTIVE'
             and (
                 (plantilla.ambito = 'STORE' and plantilla.tienda_id = tienda.id)
                 or (plantilla.ambito = 'COMPANY'
                     and plantilla.empresa_id = tienda.empresa_id
                     and plantilla.tienda_id is null)
                 or (plantilla.ambito = 'SYSTEM'
                     and plantilla.empresa_id is null
                     and plantilla.tienda_id is null)
             )
       ) then 'IMPORTED' else 'INTEGRATED' end
from tienda
cross join (values
    ('ALBARAN_VENTA', 'A4'),
    ('VALE', 'TICKET_80')
) modelo(tipo, formato)
on conflict (tienda_id, tipo, formato) do nothing;
