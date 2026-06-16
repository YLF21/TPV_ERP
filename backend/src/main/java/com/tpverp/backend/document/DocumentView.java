package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DocumentView(
        UUID id,
        TipoDocumento tipo,
        EstadoDocumento estado,
        String numero,
        LocalDate fecha,
        BigDecimal base,
        BigDecimal impuesto,
        BigDecimal total,
        String numTicket,
        String qrUrl,
        boolean origenStock) {

    public static DocumentView from(Documento document) {
        return from(document, null);
    }

    public static DocumentView from(Documento document, String qrUrl) {
        return new DocumentView(
                document.getId(), document.getTipo(), document.getEstado(),
                document.getNumero(), document.getFecha(), document.getBaseTotal(),
                document.getImpuestoTotal(), document.getTotal(),
                document.getNumTicket(), qrUrl, document.isOrigenStock());
    }
}
