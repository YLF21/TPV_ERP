package com.tpverp.backend.excel;

public record ProductImportMapping(
        String codigo,
        String codigoBarras,
        String nombre,
        String descripcion,
        String precioCompra,
        String precioVenta,
        String precioMayorista,
        String precioMiembro,
        String impuesto,
        String cantidad,
        String referenciaProveedor,
        int startRow,
        boolean updateName,
        boolean updateDescription,
        boolean updatePurchasePrice,
        boolean updateSalePrice,
        boolean updateWholesalePrice,
        boolean updateMemberPrice) {

    public int firstRowIndex() {
        return Math.max(startRow, 1) - 1;
    }
}
