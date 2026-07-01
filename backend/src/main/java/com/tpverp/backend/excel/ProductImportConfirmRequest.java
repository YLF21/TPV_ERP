package com.tpverp.backend.excel;

import com.tpverp.backend.document.CommercialDocumentType;
import java.time.LocalDate;
import java.util.UUID;

public record ProductImportConfirmRequest(
        ProductImportMapping mapping,
        UUID warehouseId,
        UUID supplierId,
        String externalNumber,
        CommercialDocumentType documentType,
        LocalDate date) {
}
