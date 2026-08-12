ALTER TABLE control_regla
    DROP CONSTRAINT IF EXISTS control_regla_tipo_ck;

ALTER TABLE control_regla
    ADD CONSTRAINT control_regla_tipo_ck CHECK (tipo IN (
        'SALE_SCREEN_CLEARED',
        'CONSECUTIVE_LINE_DELETIONS',
        'MANUAL_PRICE_CHANGE_OVER_PERCENT',
        'MANUAL_PRICE_CHANGED',
        'MANUAL_DISCOUNT_OVER_PERCENT',
        'PRODUCT_DISCOUNT_APPLIED',
        'TICKET_CANCELLED',
        'INACTIVE_PRODUCT_SOLD',
        'MANUAL_NEGATIVE_QUANTITY',
        'REFUND_POLICY_OVERRIDE',
        'CASH_DRAWER_OPENED',
        'PRODUCT_CATALOG_MODIFIED',
        'CASH_SESSION_DISCREPANCY',
        'PARKED_SALE_DELETED'
    ));

ALTER TABLE control_regla
    DROP CONSTRAINT IF EXISTS control_regla_configuracion_tipo_ck;

ALTER TABLE control_regla
    ADD CONSTRAINT control_regla_configuracion_tipo_ck CHECK (
        (tipo IN ('MANUAL_DISCOUNT_OVER_PERCENT', 'MANUAL_PRICE_CHANGE_OVER_PERCENT')
            AND configuracion ? 'thresholdPercent'
            AND configuracion - 'thresholdPercent' = '{}'::jsonb)
        OR (tipo = 'CONSECUTIVE_LINE_DELETIONS'
            AND configuracion ? 'minimumCount'
            AND configuracion - 'minimumCount' = '{}'::jsonb)
        OR (tipo IN (
                'SALE_SCREEN_CLEARED',
                'MANUAL_PRICE_CHANGED',
                'PRODUCT_DISCOUNT_APPLIED',
                'TICKET_CANCELLED',
                'INACTIVE_PRODUCT_SOLD',
                'MANUAL_NEGATIVE_QUANTITY',
                'REFUND_POLICY_OVERRIDE',
                'CASH_DRAWER_OPENED',
                'PRODUCT_CATALOG_MODIFIED',
                'CASH_SESSION_DISCREPANCY',
                'PARKED_SALE_DELETED'
            ) AND configuracion = '{}'::jsonb)
    );
