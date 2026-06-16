package com.tpverp.backend.document;

import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.verifactu.FiscalDocumentType;
import com.tpverp.backend.verifactu.FiscalRecordCommand;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordService;
import com.tpverp.backend.verifactu.VerifactuInactiveException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class DocumentFiscalIntegration {

    private static final String FORMAT_VERSION = "VERIFACTU-1";
    private static final String ALGORITHM_VERSION = "AEAT-SHA256-1";
    private static final String APPLICATION_VERSION = "TPV-ERP-0.0.1";

    private final FiscalRecordService fiscalRecords;
    private final CurrentOrganization organization;
    private final InstalacionRepository installations;

    public DocumentFiscalIntegration(
            FiscalRecordService fiscalRecords,
            CurrentOrganization organization,
            InstalacionRepository installations) {
        this.fiscalRecords = fiscalRecords;
        this.organization = organization;
        this.installations = installations;
    }

    // Registra el alta fiscal del documento de venta si VERI*FACTU esta activo.
    public void registerAlta(Documento document, boolean invoiceFromTicket) {
        var type = altaType(document, invoiceFromTicket);
        if (type != null) {
            register(document, FiscalRecordOperation.ALTA, type);
        }
    }

    // Registra la anulacion fiscal conservando el tipo original del ticket.
    public void registerTicketCancellation(Documento ticket) {
        register(ticket, FiscalRecordOperation.ANULACION, ticketType(ticket));
    }

    private FiscalDocumentType altaType(Documento document, boolean invoiceFromTicket) {
        return switch (document.getTipo()) {
            case TICKET -> ticketType(document);
            case FACTURA_VENTA -> invoiceFromTicket
                    ? FiscalDocumentType.F3 : FiscalDocumentType.F1;
            case RECTIFICATIVA_VENTA -> FiscalDocumentType.R1;
            default -> null;
        };
    }

    private FiscalDocumentType ticketType(Documento ticket) {
        return ticket.getTotal().compareTo(BigDecimal.ZERO) < 0
                ? FiscalDocumentType.R5 : FiscalDocumentType.F2;
    }

    private void register(
            Documento document, FiscalRecordOperation operation, FiscalDocumentType type) {
        try {
            fiscalRecords.register(new FiscalRecordCommand(
                    organization.currentCompany().getId(),
                    currentInstallationId(),
                    organization.currentStore().getId(),
                    document.getId(),
                    operation,
                    type,
                    FORMAT_VERSION,
                    ALGORITHM_VERSION,
                    APPLICATION_VERSION));
        } catch (VerifactuInactiveException ignored) {
            // VERI*FACTU desactivado permite operar hasta activacion legal o voluntaria.
        }
    }

    private java.util.UUID currentInstallationId() {
        return installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La instalacion no esta inicializada"))
                .getId();
    }
}
