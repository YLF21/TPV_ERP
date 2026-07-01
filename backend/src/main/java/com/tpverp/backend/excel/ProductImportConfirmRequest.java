package com.tpverp.backend.excel;

import com.tpverp.backend.document.CommercialDocumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record ProductImportConfirmRequest(
        @Valid @NotNull ProductImportMapping mapping,
        @NotNull UUID warehouseId,
        @NotNull UUID supplierId,
        String externalNumber,
        @NotNull CommercialDocumentType documentType,
        @NotNull LocalDate date) {
}
