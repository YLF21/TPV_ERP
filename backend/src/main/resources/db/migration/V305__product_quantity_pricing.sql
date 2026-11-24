alter table producto add column quantity_pricing jsonb;

alter table producto add constraint producto_quantity_pricing_object_chk
    check (quantity_pricing is null or jsonb_typeof(quantity_pricing) = 'object');
