package com.tpverp.backend.document;

public interface StockDocumentGateway {

    // Returns true only when integration has created stock movements.
    boolean confirm(CommercialDocument document);

    // Returns true only when integration has created reverse stock movements.
    boolean cancel(CommercialDocument document);
}
