package com.tpverp.backend.verifactu;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.document.CommercialDocumentType;
import java.util.EnumSet;
import org.springframework.stereotype.Component;

@Component
public class FiscalDocumentPolicy {

    private static final EnumSet<FiscalDocumentType> RECTIFICATIVE_TYPES =
            EnumSet.of(
                    FiscalDocumentType.R1, FiscalDocumentType.R2,
                    FiscalDocumentType.R3, FiscalDocumentType.R4);

    // Verifica que la operacion fiscal coincide con el tipo y estado comercial.
    public void validate(
            CommercialDocument document,
            FiscalRecordOperation operation,
            FiscalDocumentType fiscalType) {
        if (operation == FiscalRecordOperation.ANULACION) {
            validateCancellation(document);
            return;
        }
        switch (document.getTipo()) {
            case TICKET -> validateTicket(document, fiscalType);
            case FACTURA_VENTA -> {
                requireInvoiceState(document);
                if (fiscalType != FiscalDocumentType.F1
                        && fiscalType != FiscalDocumentType.F3) {
                    throw invalid("La factura de venta debe ser F1 o F3");
                }
            }
            case RECTIFICATIVA_VENTA -> {
                requireInvoiceState(document);
                if (!RECTIFICATIVE_TYPES.contains(fiscalType)) {
                    throw invalid("La rectificativa de venta debe ser R1, R2, R3 o R4");
                }
            }
            default -> throw invalid("Solo se admiten documentos de venta");
        }
    }

    private static void validateTicket(
            CommercialDocument document, FiscalDocumentType fiscalType) {
        if (document.getEstado() != DocumentStatus.CONFIRMADO) {
            throw invalid("El ticket debe estar en estado CONFIRMADO");
        }
        var expected = document.getTotal().signum() < 0
                ? FiscalDocumentType.R5 : FiscalDocumentType.F2;
        if (fiscalType != expected) {
            throw invalid("El ticket debe registrarse como " + expected);
        }
    }

    private static void validateCancellation(CommercialDocument document) {
        if (document.getTipo() != CommercialDocumentType.TICKET) {
            throw invalid("Solo puede anularse fiscalmente un ticket");
        }
        if (document.getEstado() != DocumentStatus.ANULADO) {
            throw invalid("El ticket debe estar en estado ANULADO");
        }
    }

    private static void requireInvoiceState(CommercialDocument document) {
        if (document.getEstado() != DocumentStatus.PENDIENTE
                && document.getEstado() != DocumentStatus.PAGADO) {
            throw invalid("La factura debe estar en estado PENDIENTE o PAGADO");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
