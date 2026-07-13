alter table promocion
    add column modo_agrupacion_compra varchar(24) not null default 'MIXED_TARGETS';

alter table promocion
    add constraint ck_promocion_modo_agrupacion_compra
        check (modo_agrupacion_compra in ('SAME_PRODUCT', 'MIXED_TARGETS'));

alter table promocion
    add constraint ck_promocion_configuracion_activa
        check (
            estado = 'DRAFT'
            or case tipo
                when 'PURCHASE_THRESHOLD_COUPON' then
                    minimo_importe is not null
                    and genera_cupon
                    and ((cupon_importe is not null) <> (cupon_porcentaje is not null))
                    and (cupon_valido_hasta_fecha is not null or cupon_valido_dias is not null)
                when 'PURCHASE_THRESHOLD_DISCOUNT' then
                    minimo_importe is not null
                    and ((descuento_importe is not null) <> (descuento_porcentaje is not null))
                when 'BUY_X_PAY_Y' then
                    compra_cantidad is not null
                    and paga_cantidad is not null
                    and compra_cantidad = trunc(compra_cantidad)
                    and paga_cantidad = trunc(paga_cantidad)
                    and paga_cantidad < compra_cantidad
                when 'SECOND_UNIT_PERCENT' then descuento_porcentaje is not null
                when 'FIXED_PACK_PRICE' then
                    compra_cantidad is not null
                    and compra_cantidad = trunc(compra_cantidad)
                    and precio_lote is not null
                when 'QUANTITY_DISCOUNT' then
                    minimo_cantidad is not null
                    and ((descuento_importe is not null) <> (descuento_porcentaje is not null))
                else false
            end
        );
