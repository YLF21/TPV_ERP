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
        @Valid WarehouseExcelImportMetadata excelImport,
        String excelImportProvenanceToken,
        Boolean clearExcelImport,
        String expectedExcelImportSnapshotToken) {

    public WarehouseInputCommand(
            UUID warehouseId,
            LocalDate date,
            UUID supplierId,
            String origin,
            String concept,
            List<WarehouseInputLineCommand> lines) {
        this(warehouseId, date, supplierId, origin, null, concept,
                WarehouseInputDocumentType.ENTRADA_ALMACEN, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), lines, null, null, false, null);
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
                BigDecimal.ZERO, List.of(), lines, excelImport, null, false, null);
    }

    public WarehouseInputCommand(
            UUID warehouseId,
            LocalDate date,
            UUID supplierId,
            String origin,
            String concept,
            List<WarehouseInputLineCommand> lines,
            WarehouseExcelImportMetadata excelImport,
            String excelImportProvenanceToken) {
        this(warehouseId, date, supplierId, origin, null, concept,
                WarehouseInputDocumentType.ENTRADA_ALMACEN, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), lines, excelImport, excelImportProvenanceToken, false, null);
    }

    /**
     * Source-compatible constructor for the original full command shape.
     * The provenance controls were added later and intentionally default to
     * the safe legacy values (do not clear and no expected snapshot token).
     */
    public WarehouseInputCommand(
            UUID warehouseId,
            LocalDate date,
            UUID supplierId,
            String origin,
            String externalNumber,
            String concept,
            WarehouseInputDocumentType documentType,
            WarehouseInputPriceSource priceSource,
            BigDecimal globalDiscount,
            List<UUID> sourceDeliveryNoteIds,
            List<WarehouseInputLineCommand> lines,
            WarehouseExcelImportMetadata excelImport) {
        this(warehouseId, date, supplierId, origin, externalNumber, concept,
                documentType, priceSource, globalDiscount, sourceDeliveryNoteIds,
                lines, excelImport, null, false, null);
    }

    /** Source-compatible full constructor with an apply provenance token. */
    public WarehouseInputCommand(
            UUID warehouseId,
            LocalDate date,
            UUID supplierId,
            String origin,
            String externalNumber,
            String concept,
            WarehouseInputDocumentType documentType,
            WarehouseInputPriceSource priceSource,
            BigDecimal globalDiscount,
            List<UUID> sourceDeliveryNoteIds,
            List<WarehouseInputLineCommand> lines,
            WarehouseExcelImportMetadata excelImport,
            String excelImportProvenanceToken) {
        this(warehouseId, date, supplierId, origin, externalNumber, concept,
                documentType, priceSource, globalDiscount, sourceDeliveryNoteIds,
                lines, excelImport, excelImportProvenanceToken, false, null);
    }

    public WarehouseInputCommand {
        documentType = documentType == null ? WarehouseInputDocumentType.ENTRADA_ALMACEN : documentType;
        priceSource = priceSource == null ? WarehouseInputPriceSource.PURCHASE : priceSource;
        globalDiscount = globalDiscount == null ? BigDecimal.ZERO : globalDiscount;
        sourceDeliveryNoteIds = sourceDeliveryNoteIds == null ? List.of() : List.copyOf(sourceDeliveryNoteIds);
        clearExcelImport = Boolean.TRUE.equals(clearExcelImport);
        if (excelImportProvenanceToken != null && excelImport == null) {
            throw new IllegalArgumentException("excelImportProvenanceToken requiere excelImport");
        }
        if (clearExcelImport && (excelImport != null || excelImportProvenanceToken != null
                || expectedExcelImportSnapshotToken != null)) {
            throw new IllegalArgumentException("clearExcelImport no puede combinarse con metadata o tokens");
        }
        if (excelImport != null && expectedExcelImportSnapshotToken != null) {
            throw new IllegalArgumentException("excelImport no puede combinarse con un token de snapshot");
        }
        if (excelImportProvenanceToken != null
                && !excelImportProvenanceToken.matches("WXP1\\.A\\.[A-Za-z0-9_-]{512}")) {
            throw new IllegalArgumentException("excelImportProvenanceToken no es válido");
        }
        if (expectedExcelImportSnapshotToken != null
                && !expectedExcelImportSnapshotToken.matches("WXP1\\.D\\.[A-Za-z0-9_-]{512}")) {
            throw new IllegalArgumentException("expectedExcelImportSnapshotToken no es válido");
        }
    }
}
