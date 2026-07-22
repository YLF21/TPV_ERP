package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SalesInvoiceRectificationRequest(
        @NotNull SalesInvoiceRectificationReason reason,
        @NotBlank @Size(min = 10, max = 500) String detail,
        @NotEmpty List<@Valid LineRequest> lines) {

    public record LineRequest(
            @NotNull UUID originalLineId,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitPrice) {
    }
}
