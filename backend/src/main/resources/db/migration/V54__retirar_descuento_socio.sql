-- MEMBER_DISCOUNT identificaba un beneficio exclusivo de socio. MEMBER_PRICE
-- conserva esa intencion y mantiene sincronizados ambos selectores persistidos.
update producto
set discount_type = 'MEMBER_PRICE',
    price_use_mode = 'MEMBER_PRICE'
where discount_type = 'MEMBER_DISCOUNT';
