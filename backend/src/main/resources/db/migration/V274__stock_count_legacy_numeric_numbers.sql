-- Reserve numbers while existing applications cannot allocate or edit inventory numbers.
-- Ordinary reads remain available; Flyway executes this migration in one transaction.
lock table contador_documento, recuento_stock in exclusive mode;

create temporary table stock_count_legacy_number_reservations on commit drop as
with legacy as (
    select id, tienda_id, fecha, creado_en,
           extract(year from fecha)::integer::text as periodo
    from recuento_stock
    where numero = 'INV-L-' || replace(id::text, '-', '')
), used_numbers as (
    select split_part(numero, '-', 2) as periodo,
           split_part(numero, '-', 3)::numeric as ultimo_numero
    from recuento_stock
    where numero ~ '^INV-[0-9]{4}-[0-9]{6,}$'
    union all
    select periodo, ultimo_numero::numeric
    from contador_documento
    where tipo = 'INV'
), reserved_maxima as (
    select periodo, max(ultimo_numero) as ultimo_numero
    from used_numbers
    group by periodo
)
select legacy.id, legacy.tienda_id, legacy.periodo,
       greatest(coalesce(reserved_maxima.ultimo_numero, 0), 0)
       + row_number() over (
           partition by legacy.periodo order by legacy.fecha, legacy.creado_en, legacy.id
       ) as numero_secuencia
from legacy
left join reserved_maxima using (periodo);

do $$
begin
    if exists (
        select 1 from stock_count_legacy_number_reservations
        where numero_secuencia > 2147483647
    ) then
        raise exception 'No hay numeros INV disponibles: se supera el limite del contador de inventarios';
    end if;
end;
$$;

-- Only the presentation number changes: identities, dates, states, lines and versions stay intact.
update recuento_stock inventory
set numero = 'INV-' || reservation.periodo || '-'
             || lpad(reservation.numero_secuencia::text,
                     greatest(6, length(reservation.numero_secuencia::text)), '0')
from stock_count_legacy_number_reservations reservation
where inventory.id = reservation.id;

-- numero is globally unique, although counters are scoped by store. Reserve the same
-- annual high-water mark for all existing inventory stores/counters in affected years.
with reserved_maxima as (
    select periodo, max(numero_secuencia)::integer as ultimo_numero
    from stock_count_legacy_number_reservations
    group by periodo
), affected_counters as (
    select inventory.tienda_id, maxima.periodo, maxima.ultimo_numero
    from recuento_stock inventory
    join reserved_maxima maxima on maxima.periodo = split_part(inventory.numero, '-', 2)
    where inventory.numero ~ '^INV-[0-9]{4}-[0-9]{6,}$'
    union
    select counter.tienda_id, maxima.periodo, maxima.ultimo_numero
    from contador_documento counter
    join reserved_maxima maxima using (periodo)
    where counter.tipo = 'INV'
)
insert into contador_documento (id, tienda_id, tipo, periodo, ultimo_numero, version)
select md5('stock-count-number:' || tienda_id::text || ':' || periodo)::uuid,
       tienda_id, 'INV', periodo, ultimo_numero, 0
from affected_counters
on conflict (tienda_id, tipo, periodo) do update
set ultimo_numero = excluded.ultimo_numero,
    version = contador_documento.version + 1
where contador_documento.ultimo_numero < excluded.ultimo_numero;
