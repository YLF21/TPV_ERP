package com.tpverp.frontend.common.sales;

import java.math.BigDecimal;

public record QuickCommand(BigDecimal value, Action action) {

    public enum Action {
        ADD_PRODUCT,
        SET_QUANTITY,
        LINE_DISCOUNT,
        GLOBAL_DISCOUNT,
        PACKAGES,
        CHANGE_PRICE,
        UNKNOWN
    }

    public static QuickCommand from(String rawValue, String key) {
        BigDecimal value = parseValue(rawValue);
        return new QuickCommand(value, switch (key) {
            case "ENTER" -> Action.ADD_PRODUCT;
            case "PAUSE" -> Action.SET_QUANTITY;
            case "SLASH" -> Action.LINE_DISCOUNT;
            case "CTRL_SLASH" -> Action.GLOBAL_DISCOUNT;
            case "CTRL_ASTERISK" -> Action.PACKAGES;
            case "PAGE_UP" -> Action.CHANGE_PRICE;
            default -> Action.UNKNOWN;
        });
    }

    private static BigDecimal parseValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(rawValue.trim().replace(',', '.'));
    }
}
