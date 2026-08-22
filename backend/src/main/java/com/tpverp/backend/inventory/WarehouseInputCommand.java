package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WarehouseInputCommand(
        @NotNull UUID warehouseId,
        @NotNull LocalDate date,
        UUID supplierId,
        String origin,
        String externalNumber,
        String concept,
        WarehouseInputDocumentType documentType,
        WarehouseInputPriceSource priceSource,
        @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal globalDiscount,
        List<UUID> sourceDeliveryNoteIds,
        @NotEmpty List<@Valid WarehouseInputLineCommand> lines,
        @Valid WarehouseExcelImportMetadata excelImport) {

    public WarehouseInputCommand(
            UUID warehouseId,
            LocalDate date,
            UUID supplierId,
            String origin,
            String concept,
            List<WarehouseInputLineCommand> lines) {
        this(warehouseId, date, supplierId, origin, null, concept,
                WarehouseInputDocumentType.ENTRADA_ALMACEN, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), lines, null);
    }

    public WarehouseInputCommand(
            UUID warehouseId,
            LocalDate date,
            UUID supplierId,
            String origin,
            String concept,
            List<WarehouseInputLineCommand> lines,
            WarehouseExcelImportMetadata excelImport) {
        this(warehouseId, date, supplierId, origin, null, concept,
                WarehouseInputDocumentType.ENTRADA_ALMACEN, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), lines, excelImport);
    }

    public WarehouseInputCommand {
        documentType = documentType == null ? WarehouseInputDocumentType.ENTRADA_ALMACEN : documentType;
        priceSource = priceSource == null ? WarehouseInputPriceSource.PURCHASE : priceSource;
        globalDiscount = globalDiscount == null ? BigDecimal.ZERO : globalDiscount;
        sourceDeliveryNoteIds = sourceDeliveryNoteIds == null ? List.of() : List.copyOf(sourceDeliveryNoteIds);
    }
}
