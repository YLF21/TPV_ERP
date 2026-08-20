alter table documento_linea
    drop constraint ck_documento_linea_tipo_linea;

alter table documento_linea
    add constraint ck_documento_linea_tipo_linea
        check (tipo_linea in (
            'PRODUCT', 'PROMOTION', 'PROMOTIONAL_COUPON',
            'MEMBER_BALANCE', 'MANUAL_DISCOUNT', 'RETURN_ADJUSTMENT'
        ));
