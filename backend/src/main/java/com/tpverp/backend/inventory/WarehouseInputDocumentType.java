package com.tpverp.backend.inventory;

public enum WarehouseInputDocumentType {
    ENTRADA_ALMACEN("ENT", StockMovementType.ENTRADA_ALMACEN),
    ALBARAN_ENTRADA("AE", StockMovementType.ALBARAN_ENTRADA),
    FACTURA_ENTRADA("FE", StockMovementType.FACTURA_ENTRADA);

    private final String prefix;
    private final StockMovementType movementType;

    WarehouseInputDocumentType(String prefix, StockMovementType movementType) {
        this.prefix = prefix;
        this.movementType = movementType;
    }

    public String prefix() {
        return prefix;
    }

    public StockMovementType movementType() {
        return movementType;
    }
}
