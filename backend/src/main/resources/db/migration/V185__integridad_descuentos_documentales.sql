alter table documento_linea
    drop constraint ck_documento_linea_tipo_linea;

alter table documento_linea
    add constraint ck_documento_linea_tipo_linea
        check (tipo_linea in (
            'PRODUCT', 'PROMOTION', 'PROMOTIONAL_COUPON',
            'MEMBER_BALANCE', 'MANUAL_DISCOUNT', 'DOCUMENT_DISCOUNT',
            'RETURN_ADJUSTMENT'
        )),
    add constraint ck_documento_linea_document_discount_integrity
        check (
            tipo_linea <> 'DOCUMENT_DISCOUNT'
            or (
                producto_id is null
                and promocion_id is null
                and promocion_version_id is null
                and cupon_promocional_id is null
                and documento_ajuste_id is not null
                and linea_origen_id is not null
                and linea_origen_id <> id
                and total < 0
            )
        );
