ALTER TABLE documento_linea
    DROP CONSTRAINT ck_documento_linea_tipo_linea;

ALTER TABLE documento_linea
    ADD CONSTRAINT ck_documento_linea_tipo_linea
        CHECK (tipo_linea IN (
            'PRODUCT', 'PROMOTION', 'PROMOTIONAL_COUPON', 'MANUAL_DISCOUNT'
        )),
    ADD CONSTRAINT ck_documento_linea_manual_discount_integrity
        CHECK (
            tipo_linea <> 'MANUAL_DISCOUNT'
            OR (
                producto_id IS NULL
                AND promocion_id IS NULL
                AND promocion_version_id IS NULL
                AND cupon_promocional_id IS NULL
                AND total < 0
            )
        );
