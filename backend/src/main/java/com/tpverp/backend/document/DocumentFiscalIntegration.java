package com.tpverp.backend.document;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.verifactu.FiscalDocumentType;
import com.tpverp.backend.verifactu.FiscalRecordCommand;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import com.tpverp.backend.verifactu.FiscalRecordService;
import com.tpverp.backend.verifactu.VerifactuInactiveException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import com.tpverp.backend.verifactu.FiscalRecordQueuedEvent;

@Component
public class DocumentFiscalIntegration {

    private static final String FORMAT_VERSION = "VERIFACTU-1";
    private static final String ALGORITHM_VERSION = "AEAT-SHA256-1";
    private static final String APPLICATION_VERSION = "TPV-ERP-0.0.1";

    private final FiscalRecordService fiscalRecords;
    private final FiscalRecordRepository recordRepository;
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final ApplicationEventPublisher events;

    public DocumentFiscalIntegration(
            FiscalRecordService fiscalRecords,
            FiscalRecordRepository recordRepository,
            CurrentOrganization organization,
            InstallationRepository installations,
            ApplicationEventPublisher events) {
        this.fiscalRecords = fiscalRecords;
        this.recordRepository = recordRepository;
        this.organization = organization;
        this.installations = installations;
        this.events = events;
    }

    // Registra el alta fiscal del documento de venta si VERI*FACTU esta activo.
    public void registerAlta(CommercialDocument document, boolean invoiceFromTicket) {
        var type = altaType(document, invoiceFromTicket);
        if (type != null) {
            register(document, FiscalRecordOperation.ALTA, type);
        }
    }

    // Registra la anulacion fiscal conservando el tipo original del ticket.
    public void registerTicketCancellation(CommercialDocument ticket) {
        register(ticket, FiscalRecordOperation.ANULACION, ticketType(ticket));
    }

    public void registerInvoiceFromTicket(CommercialDocument invoice, CommercialDocument ticket) {
        try {
            var record = fiscalRecords.registerSubstitution(
                    command(invoice, FiscalRecordOperation.ALTA, FiscalDocumentType.F3),
                    ticket.getId());
            events.publishEvent(new FiscalRecordQueuedEvent(record.getId()));
        } catch (VerifactuInactiveException ignored) {
            // La conversión comercial sigue disponible antes de activate VERI*FACTU.
        }
    }
    // Registra F3 y enlaza fiscalmente la factura simplificada sustituida.

    public boolean hasFiscalRecord(java.util.UUID documentId) {
        return recordRepository.findByDocumentIdAndOperation(
                documentId, FiscalRecordOperation.ALTA).isPresent();
    }
    // Indica si el contenido fiscal del documento ya quedo congelado.

    private FiscalDocumentType altaType(CommercialDocument document, boolean invoiceFromTicket) {
        return switch (document.getTipo()) {
            case TICKET -> ticketType(document);
            case FACTURA_VENTA -> invoiceFromTicket
                    ? FiscalDocumentType.F3 : FiscalDocumentType.F1;
            case RECTIFICATIVA_VENTA -> FiscalDocumentType.R1;
            default -> null;
        };
    }

    private FiscalDocumentType ticketType(CommercialDocument ticket) {
        return ticket.getTotal().compareTo(BigDecimal.ZERO) < 0
                ? FiscalDocumentType.R5 : FiscalDocumentType.F2;
    }

    private void register(
            CommercialDocument document, FiscalRecordOperation operation, FiscalDocumentType type) {
        try {
            var record = fiscalRecords.register(command(document, operation, type));
            events.publishEvent(new FiscalRecordQueuedEvent(record.getId()));
        } catch (VerifactuInactiveException ignored) {
            // VERI*FACTU desactivado permite operar hasta activacion legal o voluntaria.
        }
    }

    private FiscalRecordCommand command(
            CommercialDocument document, FiscalRecordOperation operation, FiscalDocumentType type) {
        return new FiscalRecordCommand(
                organization.currentCompany().getId(), currentInstallationId(),
                organization.currentStore().getId(), document.getId(), operation, type,
                FORMAT_VERSION, ALGORITHM_VERSION, APPLICATION_VERSION);
    }

    private java.util.UUID currentInstallationId() {
        return installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La instalacion no esta inicializada"))
                .getId();
    }
}
