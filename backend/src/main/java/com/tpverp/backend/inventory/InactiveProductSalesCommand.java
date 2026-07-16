package com.tpverp.backend.inventory;

import jakarta.validation.constraints.NotNull;

public record InactiveProductSalesCommand(
        @NotNull Boolean allowInactiveProductSales) {
}
