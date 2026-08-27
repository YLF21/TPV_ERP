alter table plantilla_documento
    drop constraint ck_plantilla_documento_motivo_retirada;

alter table plantilla_documento
    add constraint ck_plantilla_documento_motivo_retirada
        check (motivo_retirada is null or motivo_retirada in (
            'REPLACED_BY_TEMPLATE',
            'BUILT_IN_DESIGN_SELECTED',
            'FISCAL_VISUAL_VALIDATION_REQUIRED'
        ));

-- Return affected stores to the trusted integrated route before retiring the
-- custom rows. Artifacts and historical snapshot references remain untouched.
with affected_routes as (
    select distinct tienda.id as tienda_id,
           plantilla.tipo,
           plantilla.formato
    from plantilla_documento plantilla
    join tienda
      on (plantilla.ambito = 'STORE' and tienda.id = plantilla.tienda_id)
      or (plantilla.ambito = 'COMPANY' and tienda.empresa_id = plantilla.empresa_id)
    where plantilla.ambito in ('COMPANY', 'STORE')
      and plantilla.estado = 'ACTIVE'
      and plantilla.tipo in ('TICKET', 'FACTURA_VENTA', 'RECTIFICATIVA_VENTA')
)
update configuracion_origen_plantilla_documento configuracion
set origen = 'INTEGRATED',
    version = configuracion.version + 1
from affected_routes affected
where configuracion.tienda_id = affected.tienda_id
  and configuracion.tipo = affected.tipo
  and configuracion.formato = affected.formato
  and configuracion.origen <> 'INTEGRATED';

update plantilla_documento
set estado = 'RETIRED',
    retirada_en = coalesce(retirada_en, current_timestamp),
    motivo_retirada = 'FISCAL_VISUAL_VALIDATION_REQUIRED',
    version = version + 1
where ambito in ('COMPANY', 'STORE')
  and estado = 'ACTIVE'
  and tipo in ('TICKET', 'FACTURA_VENTA', 'RECTIFICATIVA_VENTA');
