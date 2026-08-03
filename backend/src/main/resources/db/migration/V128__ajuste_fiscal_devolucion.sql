alter table documento_linea
    drop constraint ck_documento_linea_tipo_linea;

alter table documento_linea
    add constraint ck_documento_linea_tipo_linea
        check (tipo_linea in (
            'PRODUCT', 'PROMOTION', 'PROMOTIONAL_COUPON',
            'MANUAL_DISCOUNT', 'RETURN_ADJUSTMENT'
        )),
    add constraint ck_documento_linea_return_adjustment_integrity
        check (
            tipo_linea <> 'RETURN_ADJUSTMENT'
            or (
                producto_id is null
                and promocion_id is null
                and promocion_version_id is null
                and cupon_promocional_id is null
            )
        );
