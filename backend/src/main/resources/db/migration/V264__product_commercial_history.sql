-- Configuration snapshots, not a reconstruction of past sales or earlier campaigns.
create table producto_historial_comercial (
    id uuid primary key default gen_random_uuid(),
    producto_id uuid not null references producto(id) on delete cascade,
    tienda_id uuid not null references tienda(id),
    source varchar(16) not null check (source in ('OFFER', 'PROMOTION')),
    source_id uuid not null,
    snapshot jsonb not null check (jsonb_typeof(snapshot) = 'object'),
    registrado_en timestamptz not null default clock_timestamp(),
    initial_snapshot boolean not null default false,
    sequence_id bigint generated always as identity unique
);
create index ix_producto_historial_comercial_producto
    on producto_historial_comercial(producto_id, sequence_id desc);
create index ix_producto_historial_comercial_source
    on producto_historial_comercial(producto_id, source, source_id, sequence_id desc);
create index ix_producto_historial_comercial_promocion
    on producto_historial_comercial(source_id) where source = 'PROMOTION';

create function product_commercial_history_record(
    p_product uuid, p_store uuid, p_source text, p_origin uuid,
    p_snapshot jsonb, p_initial boolean default false
) returns void language plpgsql as $$
declare previous_snapshot jsonb;
begin
    perform pg_advisory_xact_lock(hashtextextended(
        p_product::text || ':' || p_source || ':' || p_origin::text, 264));
    select snapshot into previous_snapshot
      from producto_historial_comercial
     where producto_id = p_product and source = p_source and source_id = p_origin
     order by sequence_id desc limit 1;
    if previous_snapshot is distinct from p_snapshot then
        insert into producto_historial_comercial
            (producto_id, tienda_id, source, source_id, snapshot, initial_snapshot)
        values (p_product, p_store, p_source, p_origin, p_snapshot, p_initial);
    end if;
end;
$$;

create function product_commercial_history_capture(
    p_products uuid[], p_initial boolean default false, p_offers_only boolean default false
) returns void language plpgsql as $$
declare
    product_row record;
    offer_snapshot jsonb;
    previous_offer jsonb;
    offer_type text;
    offer_final numeric;
    offer_percent numeric;
    business_date date;
    offer_status text;
begin
    for product_row in
        select p.*, s.timezone as store_timezone,
               sale.importe as sale_price, offer.importe as offer_price
          from producto p join tienda s on s.id = p.tienda_id
          left join producto_precio sale on sale.producto_id = p.id and sale.tarifa = 'VENTA'
          left join producto_precio offer on offer.producto_id = p.id and offer.tarifa = 'OFERTA'
         where p.id = any(p_products) order by p.id
    loop
        business_date := (current_timestamp at time zone product_row.store_timezone)::date;
        previous_offer := null;
        select snapshot into previous_offer from producto_historial_comercial
         where producto_id = product_row.id and source = 'OFFER'
         order by sequence_id desc limit 1;
        if product_row.oferta_activa or product_row.price_use_mode in ('OFFER_PRICE', 'OFFER_DISCOUNT')
           or (p_initial and product_row.oferta_desde is not null
               and (product_row.offer_price is not null or product_row.oferta_descuento_porcentaje is not null)) then
            offer_type := case when product_row.price_use_mode in ('OFFER_PRICE','OFFER_DISCOUNT')
                then product_row.price_use_mode else 'OFFER_UNKNOWN' end;
            offer_final := case offer_type
                when 'OFFER_PRICE' then product_row.offer_price
                when 'OFFER_DISCOUNT' then round(product_row.sale_price *
                    (1 - product_row.oferta_descuento_porcentaje / 100), 3)
                else null end;
            offer_percent := case
                when offer_type = 'OFFER_DISCOUNT' then product_row.oferta_descuento_porcentaje
                when offer_final is not null and product_row.sale_price > 0 then
                    round((product_row.sale_price - offer_final) * 100 / product_row.sale_price, 2)
                else null end;
            offer_status := case
                when product_row.oferta_hasta < business_date then 'EXPIRED'
                when not product_row.oferta_activa then 'INACTIVE'
                else 'ACTIVE' end;
            offer_snapshot := jsonb_build_object(
                'type', offer_type, 'name', null,
                'dateFrom', product_row.oferta_desde, 'dateTo', product_row.oferta_hasta,
                'beforePrice', product_row.sale_price, 'finalPrice', offer_final,
                'discountPercent', offer_percent, 'status', offer_status,
                'conditions', jsonb_build_object('offerPrice', product_row.offer_price,
                    'offerDiscountPercent', product_row.oferta_descuento_porcentaje,
                    'taxesIncluded', product_row.impuestos_incluidos));
            perform product_commercial_history_record(product_row.id, product_row.tienda_id,
                'OFFER', product_row.id, offer_snapshot, p_initial);
        elsif previous_offer is not null then
            -- Keep the earlier commercial values when expiration resets the price mode.
            offer_status := case when (previous_offer->>'dateTo')::date < business_date
                then 'EXPIRED' else 'INACTIVE' end;
            perform product_commercial_history_record(product_row.id, product_row.tienda_id,
                'OFFER', product_row.id,
                jsonb_set(previous_offer, '{status}', to_jsonb(offer_status)), p_initial);
        end if;
    end loop;

    if p_offers_only then return; end if;

    perform product_commercial_history_record(p.id, p.tienda_id, 'PROMOTION', m.id,
        jsonb_build_object('type', m.tipo, 'name', m.nombre,
            'dateFrom', m.fecha_inicio, 'dateTo', m.fecha_fin,
            'beforePrice', sale.importe, 'finalPrice', null, 'discountPercent', null,
            'status', case when m.estado = 'ACTIVE' and m.fecha_fin <
                (current_timestamp at time zone s.timezone)::date then 'EXPIRED' else m.estado end,
            'conditions', (to_jsonb(m) - array['usada','creado_en','actualizado_en','version']) ||
                jsonb_build_object('targets', coalesce((
                    select jsonb_agg(jsonb_build_object('type', o.tipo, 'targetId', o.objetivo_id)
                        order by o.tipo, o.objetivo_id)
                    from promocion_objetivo o where o.promocion_id = m.id), '[]'::jsonb))), p_initial)
      from producto p join tienda s on s.id = p.tienda_id
      join promocion m on m.empresa_id = s.empresa_id and m.estado <> 'DRAFT'
      left join producto_precio sale on sale.producto_id = p.id and sale.tarifa = 'VENTA'
     where p.id = any(p_products)
       and (m.ambito = 'SALE' or exists (
           select 1 from promocion_objetivo o where o.promocion_id = m.id and
               ((m.ambito = 'PRODUCT_LIST' and o.tipo = 'PRODUCT' and o.objetivo_id = p.id)
                or (m.ambito = 'FAMILY' and o.tipo = 'FAMILY' and o.objetivo_id = p.familia_id)
                or (m.ambito = 'SUBFAMILY' and o.tipo = 'SUBFAMILY' and o.objetivo_id = p.subfamilia_id))))
     order by p.id, m.id;

    -- A changed classification must not rewrite the product's earlier membership.
    perform product_commercial_history_record(h.producto_id, h.tienda_id, 'PROMOTION', h.source_id,
        jsonb_set(h.snapshot, '{status}', '"REMOVED"'::jsonb), p_initial)
      from (select distinct on (producto_id, source_id) *
              from producto_historial_comercial where producto_id = any(p_products) and source = 'PROMOTION'
             order by producto_id, source_id, sequence_id desc) h
      join producto p on p.id = h.producto_id
      join tienda s on s.id = p.tienda_id
     where h.snapshot->>'status' <> 'REMOVED'
       and not exists (select 1 from promocion m
            where m.id = h.source_id and m.empresa_id = s.empresa_id and m.estado <> 'DRAFT'
              and (m.ambito = 'SALE' or exists (
                  select 1 from promocion_objetivo o where o.promocion_id = m.id and
                      ((m.ambito = 'PRODUCT_LIST' and o.tipo = 'PRODUCT' and o.objetivo_id = p.id)
                       or (m.ambito = 'FAMILY' and o.tipo = 'FAMILY' and o.objetivo_id = p.familia_id)
                       or (m.ambito = 'SUBFAMILY' and o.tipo = 'SUBFAMILY' and o.objetivo_id = p.subfamilia_id)))))
     order by h.producto_id, h.source_id;
end;
$$;

-- Snapshot only the surviving configuration. initial_snapshot distinguishes this baseline.
do $$
declare product_ids uuid[];
begin
    for product_ids in
        select array_agg(id) from (
            select id, (row_number() over (order by id) - 1) / 500 as batch from producto
        ) batches group by batch
    loop
        perform product_commercial_history_capture(product_ids, true);
    end loop;
end;
$$;
