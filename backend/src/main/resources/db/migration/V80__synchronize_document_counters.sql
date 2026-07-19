with existing_numbers as (
    select tienda_id,
           case tipo
               when 'ALBARAN_VENTA' then 'AV'
               when 'ALBARAN_COMPRA' then 'AC'
               when 'TICKET' then 'T'
               when 'FACTURA_VENTA' then 'FV'
               when 'FACTURA_COMPRA' then 'FC'
               when 'RECTIFICATIVA_VENTA' then 'FRV'
               when 'RECTIFICATIVA_COMPRA' then 'FRC'
           end as tipo,
           case when tipo = 'TICKET'
                then to_char(fecha, 'YYYYMMDD')
                else extract(year from fecha)::integer::text
           end as periodo,
           max(substring(numero from '([0-9]+)$')::integer) as ultimo_numero
    from documento
    where numero ~ '[0-9]+$'
    group by tienda_id,
             tipo,
             case when tipo = 'TICKET'
                  then to_char(fecha, 'YYYYMMDD')
                  else extract(year from fecha)::integer::text
             end
)
insert into contador_documento
    (id, tienda_id, tipo, periodo, ultimo_numero, version)
select md5(tienda_id::text || ':' || tipo || ':' || periodo)::uuid,
       tienda_id,
       tipo,
       periodo,
       ultimo_numero,
       0
from existing_numbers
where tipo is not null
on conflict (tienda_id, tipo, periodo) do update
set ultimo_numero = greatest(
    contador_documento.ultimo_numero,
    excluded.ultimo_numero);
