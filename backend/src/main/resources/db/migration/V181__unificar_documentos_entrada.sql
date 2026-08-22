-- Los documentos de compra operativos pasan a la misma tabla que las entradas.
-- Se conservan las filas comerciales de origen mientras existan referencias de
-- auditoria, pero dejan de ser una fuente funcional para la aplicacion.
create temporary table tmp_documentos_entrada_migrados (
    documento_id uuid primary key,
    tipo_documento varchar(32) not null
) on commit drop;

insert into tmp_documentos_entrada_migrados (documento_id, tipo_documento)
select id,
       case tipo
           when 'ALBARAN_COMPRA' then 'ALBARAN_ENTRADA'
           when 'FACTURA_COMPRA' then 'FACTURA_ENTRADA'
       end
from documento
where tipo in ('ALBARAN_COMPRA', 'FACTURA_COMPRA')
  and estado <> 'ANULADO';

insert into entrada_almacen (
    id, tienda_id, almacen_id, proveedor_id, tipo_documento, numero,
    fecha, estado, numero_externo, fuente_precio, descuento_global,
    creada_por, confirmada_por, confirmada_en, version)
select document.id,
       document.tienda_id,
       document.almacen_id,
       document.proveedor_id,
       migrated.tipo_documento,
       document.numero,
       document.fecha,
       case when document.estado = 'BORRADOR' then 'BORRADOR' else 'CONFIRMADA' end,
       document.numero_externo,
       'PURCHASE',
       document.descuento_global,
       document.creado_por,
       case when document.estado = 'BORRADOR' then null else coalesce(document.confirmado_por, document.creado_por) end,
       case when document.estado = 'BORRADOR' then null else coalesce(document.confirmado_en, document.creado_en) end,
       document.version
from documento document
join tmp_documentos_entrada_migrados migrated on migrated.documento_id = document.id
where not exists (select 1 from entrada_almacen input where input.id = document.id);

insert into entrada_almacen_linea (
    id, entrada_id, producto_id, cantidad, precio_unitario_compra,
    descuento, precio_personalizado, version)
select line.id,
       line.documento_id,
       line.producto_id,
       abs(line.cantidad)::numeric(19,3),
       line.precio_unitario,
       line.descuento,
       true,
       line.version
from documento_linea line
join tmp_documentos_entrada_migrados migrated on migrated.documento_id = line.documento_id
where line.tipo_linea = 'PRODUCT'
  and line.producto_id is not null
  and line.cantidad > 0
  and not exists (select 1 from entrada_almacen_linea input_line where input_line.id = line.id);

insert into entrada_almacen_albaran_origen (factura_id, albaran_id)
select invoice.id, delivery.id
from documento_relacion relation
join documento invoice on invoice.id = relation.documento_id
join documento delivery on delivery.id = relation.origen_id
join tmp_documentos_entrada_migrados invoice_migrated on invoice_migrated.documento_id = invoice.id
join tmp_documentos_entrada_migrados delivery_migrated on delivery_migrated.documento_id = delivery.id
where relation.tipo = 'FACTURA_DE'
  and invoice.tipo = 'FACTURA_COMPRA'
  and delivery.tipo = 'ALBARAN_COMPRA'
  and not exists (
      select 1
      from entrada_almacen_albaran_origen relation_input
      where relation_input.factura_id = invoice.id
        and relation_input.albaran_id = delivery.id
  );

update movimiento_stock movement
set entrada_almacen_id = movement.documento_id,
    tipo = case document.tipo
        when 'ALBARAN_COMPRA' then 'ALBARAN_ENTRADA'
        when 'FACTURA_COMPRA' then 'FACTURA_ENTRADA'
    end
from documento document
join tmp_documentos_entrada_migrados migrated on migrated.documento_id = document.id
where movement.documento_id = document.id
  and document.tipo in ('ALBARAN_COMPRA', 'FACTURA_COMPRA');

delete from comprobacion_mercancia check_record
where check_record.documento_id in (
    select id
    from documento
    where tipo = 'RECTIFICATIVA_COMPRA'
       or (tipo in ('ALBARAN_COMPRA', 'FACTURA_COMPRA') and estado = 'ANULADO')
);

alter table comprobacion_mercancia
    drop constraint if exists comprobacion_mercancia_documento_id_fkey;

alter table comprobacion_mercancia
    add constraint comprobacion_mercancia_entrada_id_fkey
        foreign key (documento_id) references entrada_almacen(id);
