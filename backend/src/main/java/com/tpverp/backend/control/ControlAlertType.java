package com.tpverp.backend.control;

import java.util.Map;

public enum ControlAlertType {
    SALE_SCREEN_CLEARED(
            "Eliminacion completa de carrito", ControlRuleParameterKind.NONE, Map.of(), true),
    CONSECUTIVE_LINE_DELETIONS(
            "Eliminacion de lineas consecutivas", ControlRuleParameterKind.QUANTITY,
            Map.of(ControlRuleConfiguration.MINIMUM_COUNT, 3), true),
    MANUAL_PRICE_CHANGE_OVER_PERCENT(
            "Cambio manual de precio superior al porcentaje", ControlRuleParameterKind.PERCENTAGE,
            Map.of(ControlRuleConfiguration.THRESHOLD_PERCENT, 10), false),
    MANUAL_PRICE_CHANGED(
            "Cambio manual de precio", ControlRuleParameterKind.NONE, Map.of(), false),
    MANUAL_DISCOUNT_OVER_PERCENT(
            "Descuento manual superior al porcentaje", ControlRuleParameterKind.PERCENTAGE,
            Map.of(ControlRuleConfiguration.THRESHOLD_PERCENT, 10), true),
    PRODUCT_DISCOUNT_APPLIED(
            "Descuento manual aplicado a producto", ControlRuleParameterKind.NONE, Map.of(), true),
    TICKET_CANCELLED(
            "Anulacion de ticket", ControlRuleParameterKind.NONE, Map.of(), true),
    INACTIVE_PRODUCT_SOLD(
            "Venta de producto desactivado", ControlRuleParameterKind.NONE, Map.of(), true);

    private final String systemName;
    private final ControlRuleParameterKind parameterKind;
    private final Map<String, Object> defaultConfiguration;
    private final boolean supported;

    ControlAlertType(
            String systemName,
            ControlRuleParameterKind parameterKind,
            Map<String, Object> defaultConfiguration,
            boolean supported) {
        this.systemName = systemName;
        this.parameterKind = parameterKind;
        this.defaultConfiguration = Map.copyOf(defaultConfiguration);
        this.supported = supported;
    }

    public String systemName() {
        return systemName;
    }

    public ControlRuleParameterKind parameterKind() {
        return parameterKind;
    }

    public Map<String, Object> defaultConfiguration() {
        return defaultConfiguration;
    }

    public boolean supported() {
        return supported;
    }
}
