package com.tpverp.backend.document;

public interface StockDocumentGateway {

    // Devuelve true solo cuando la integración ha creado movimientos de stock.
    boolean confirm(CommercialDocument document);

    // Devuelve true solo cuando la integración ha creado movimientos inversos.
    boolean cancel(CommercialDocument document);
}
