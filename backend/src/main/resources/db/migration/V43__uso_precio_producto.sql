alter table producto
    add column price_use_mode varchar(32) not null default 'NORMAL',
    add column oferta_descuento_porcentaje numeric(5,2);

update producto
set price_use_mode = case
    when discount_type = 'MEMBER_PRICE' then 'MEMBER_PRICE'
    when discount_type = 'DISCOUNT_PRICE' then 'OFFER_PRICE'
    else 'NORMAL'
end;

alter table producto
    add constraint ck_producto_price_use_mode
        check (price_use_mode in ('NORMAL', 'MEMBER_PRICE', 'OFFER_PRICE', 'OFFER_DISCOUNT')),
    add constraint ck_producto_oferta_descuento_porcentaje
        check (oferta_descuento_porcentaje is null or oferta_descuento_porcentaje between 0 and 100);
