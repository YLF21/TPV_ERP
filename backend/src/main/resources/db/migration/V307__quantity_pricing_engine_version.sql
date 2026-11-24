-- Preserve existing NULL/v2 markers; only newly quoted quantity-pricing baskets use v3.
alter table documento_linea drop constraint ck_documento_linea_promocion_motor_version;
alter table documento_linea add constraint ck_documento_linea_promocion_motor_version check (
    promocion_motor_version is null
    or (tipo_linea in ('PROMOTION','QUANTITY_PRICE') and promocion_motor_version in (2,3))
    or (tipo_linea = 'PRODUCT' and precio_cantidad_snapshot is not null and promocion_motor_version = 3)
);
