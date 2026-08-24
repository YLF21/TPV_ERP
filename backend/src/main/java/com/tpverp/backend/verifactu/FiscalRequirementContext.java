package com.tpverp.backend.verifactu;

/** Contexto oficial de una remision de registros motivada por requerimiento AEAT. */
public record FiscalRequirementContext(String reference, boolean finished) {

    public FiscalRequirementContext {
        reference = reference == null ? "" : reference.trim();
        if (reference.isBlank()) {
            throw new IllegalArgumentException("La referencia del requerimiento es obligatoria");
        }
        if (reference.length() > 18) {
            throw new IllegalArgumentException(
                    "La referencia del requerimiento no puede superar 18 caracteres");
        }
    }

    public String finishedValue() {
        return finished ? "S" : "N";
    }
}
