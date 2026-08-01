package com.tpverp.backend.security.sales;

import java.util.List;
import java.util.Objects;

public record SaleOperationDefinition(
        SaleOperationCode code,
        SaleOperationCategory category,
        List<String> shortcuts,
        List<String> permissions,
        boolean defaultRequirePermission,
        boolean defaultRequirePassword) {

    public SaleOperationDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        shortcuts = List.copyOf(Objects.requireNonNull(shortcuts, "shortcuts"));
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (permissions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("permissions contains a blank value");
        }
    }
}
