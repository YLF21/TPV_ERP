alter table documento_linea
    add column precio_cantidad_snapshot jsonb,
    drop constraint ck_documento_linea_tipo_linea,
    drop constraint ck_documento_linea_promocion_motor_version;

alter table documento_linea
    add constraint ck_documento_linea_promocion_motor_version check (
        promocion_motor_version is null or (tipo_linea in ('PROMOTION','QUANTITY_PRICE') and promocion_motor_version = 2)
    ),
    add constraint ck_documento_linea_tipo_linea check (tipo_linea in (
        'PRODUCT', 'PROMOTION', 'PROMOTIONAL_COUPON', 'MEMBER_BALANCE', 'MANUAL_DISCOUNT',
        'DOCUMENT_DISCOUNT', 'RETURN_ADJUSTMENT', 'QUANTITY_PRICE'
    )),
    add constraint ck_documento_linea_precio_cantidad_snapshot check (
        precio_cantidad_snapshot is null or (tipo_linea = 'PRODUCT' and jsonb_typeof(precio_cantidad_snapshot) = 'object')
    ),
    add constraint ck_documento_linea_precio_cantidad check (
        tipo_linea <> 'QUANTITY_PRICE' or (producto_id is null and promocion_id is null
            and promocion_version_id is null and cupon_promocional_id is null)
    );
