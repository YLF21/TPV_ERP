package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentCommand(
        UUID almacenId,
        TipoDocumento tipo,
        LocalDate fecha,
        UUID clienteId,
        UUID proveedorId,
        String numeroExterno,
        BigDecimal descuentoGlobal,
        boolean directo,
        List<DocumentLineCommand> lineas) {
}
