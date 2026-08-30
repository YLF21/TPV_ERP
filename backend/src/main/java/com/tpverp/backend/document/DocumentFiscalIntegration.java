package com.tpverp.backend.document;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.verifactu.FiscalDocumentType;
import com.tpverp.backend.verifactu.FiscalRecordCommand;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import com.tpverp.backend.verifactu.FiscalRecordService;
import com.tpverp.backend.verifactu.FiscalRectificationMethod;
import com.tpverp.backend.verifactu.FiscalInstallationResolver;
import com.tpverp.backend.verifactu.VerifactuInactiveException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import com.tpverp.backend.verifactu.FiscalRecordQueuedEvent;
import com.tpverp.backend.verifactu.FiscalRuntimeProperties;
import org.springframework.beans.factory.annotation.Value;

@Component
public class DocumentFiscalIntegration {

    private static final String FORMAT_VERSION = "VERIFACTU-1";
    private static final String ALGORITHM_VERSION = "AEAT-SHA256-1";
    private String applicationVersion = "4.2.0";

    private final FiscalRecordService fiscalRecords;
    private final FiscalRecordRepository recordRepository;
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final ApplicationEventPublisher events;
    private final SalesInvoiceRectificationService rectifications;
    private final InstallationStatusService installationStatus;
    private final LicenseRepository licenses;
    private FiscalRuntimeProperties runtimeProperties;

    public DocumentFiscalIntegration(
            FiscalRecordService fiscalRecords,
            FiscalRecordRepository recordRepository,
            CurrentOrganization organization,
            InstallationRepository installations,
            ApplicationEventPublisher events,
            SalesInvoiceRectificationService rectifications,
            InstallationStatusService installationStatus) {
        this(fiscalRecords, recordRepository, organization, installations, events,
                rectifications, installationStatus, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentFiscalIntegration(
            FiscalRecordService fiscalRecords,
            FiscalRecordRepository recordRepository,
            CurrentOrganization organization,
            InstallationRepository installations,
            ApplicationEventPublisher events,
            SalesInvoiceRectificationService rectifications,
            InstallationStatusService installationStatus,
            LicenseRepository licenses) {
        this.fiscalRecords = fiscalRecords;
        this.recordRepository = recordRepository;
        this.organization = organization;
        this.installations = installations;
        this.events = events;
        this.rectifications = rectifications;
        this.installationStatus = installationStatus;
        this.licenses = licenses;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFiscalRuntimeProperties(FiscalRuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
        if (runtimeProperties.isSandbox()) {
            this.applicationVersion = runtimeProperties.systemVersion();
        }
    }

    /** The frozen fiscal record must carry the build version used to produce it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setApplicationVersion(
            @Value("${tpv.verifactu.system-version:4.2.0}") String applicationVersion) {
        if (applicationVersion != null && !applicationVersion.isBlank()) {
            this.applicationVersion = applicationVersion.trim();
        }
    }

    // Registers the sales document fiscal creation when the configured SIF mode requires it.
    public void registerAlta(CommercialDocument document, boolean invoiceFromTicket) {
        if (skipFiscalRegistration()) {
            return;
        }
        if (document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA) {
            registerSalesRectification(document);
            return;
        }
        var type = altaType(document, invoiceFromTicket);
        if (type == FiscalDocumentType.R5) {
            throw new IllegalStateException(
                    "Una factura simplificada R5 requiere el flujo de devolucion vinculado");
        }
        if (type != null) {
            register(document, FiscalRecordOperation.ALTA, type);
        }
    }

    // Registers fiscal cancellation while preserving the original ticket type.
    public void registerTicketCancellation(CommercialDocument ticket) {
        if (skipFiscalRegistration()) {
            return;
        }
        register(ticket, FiscalRecordOperation.ANULACION, ticketType(ticket));
    }

    public void registerInvoiceFromTicket(CommercialDocument invoice, CommercialDocument ticket) {
        if (skipFiscalRegistration()) {
            return;
        }
        try {
            var record = fiscalRecords.registerSubstitution(
                    command(invoice, FiscalRecordOperation.ALTA, FiscalDocumentType.F3),
                    ticket.getId());
            publishQueuedIfNeeded(record);
        } catch (VerifactuInactiveException ignored) {
            // Commercial conversion remains available before VERI*FACTU activation.
        }
    }
    // Registers F3 and fiscally links the replaced simplified invoice.

    public void registerTicketRectification(
            CommercialDocument rectification, CommercialDocument original) {
        if (skipFiscalRegistration()) {
            return;
        }
        registerRectification(rectification, original.getId(), FiscalDocumentType.R5,
                FiscalRectificationMethod.I);
    }

    public SalesInvoiceRectification validateBeforeConfirmation(CommercialDocument document) {
        return rectifications.validateBeforeConfirmation(document);
    }

    public boolean hasFiscalRecord(java.util.UUID documentId) {
        return recordRepository.findByDocumentIdAndOperation(
                documentId, FiscalRecordOperation.ALTA).isPresent();
    }
    // Indicates whether the document fiscal content is already frozen.

    private FiscalDocumentType altaType(CommercialDocument document, boolean invoiceFromTicket) {
        return switch (document.getTipo()) {
            case TICKET -> ticketType(document);
            case FACTURA_VENTA -> invoiceFromTicket
                    ? FiscalDocumentType.F3 : FiscalDocumentType.F1;
            case RECTIFICATIVA_VENTA -> null;
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
            publishQueuedIfNeeded(record);
        } catch (VerifactuInactiveException ignored) {
            // VERI*FACTU desactivado permite operar hasta la fecha automatica
            // de licencia o la activacion voluntaria.
        }
    }

    private void registerSalesRectification(CommercialDocument document) {
        var metadata = rectifications.validateBeforeConfirmation(document);
        registerRectification(
                document,
                metadata.getOriginalDocumentId(),
                FiscalDocumentType.valueOf(metadata.getFiscalType().name()),
                FiscalRectificationMethod.valueOf(metadata.getMethod().name()));
    }

    private void registerRectification(
            CommercialDocument document,
            java.util.UUID originalDocumentId,
            FiscalDocumentType type,
            FiscalRectificationMethod method) {
        try {
            var record = fiscalRecords.registerRectification(
                    command(document, FiscalRecordOperation.ALTA, type),
                    originalDocumentId,
                    method);
            publishQueuedIfNeeded(record);
        } catch (VerifactuInactiveException ignored) {
            // La relacion comercial permanece obligatoria aun sin activacion fiscal.
        }
    }

    private FiscalRecordCommand command(
            CommercialDocument document, FiscalRecordOperation operation, FiscalDocumentType type) {
        return new FiscalRecordCommand(
                organization.currentCompany().getId(), currentInstallationId(),
                organization.currentStore().getId(), document.getId(), operation, type,
                FORMAT_VERSION, ALGORITHM_VERSION, applicationVersion);
    }

    private java.util.UUID currentInstallationId() {
        return FiscalInstallationResolver.resolveCurrent(organization, installations, licenses).getId();
    }

    private boolean skipFiscalRegistration() {
        // The historical unlicensed DEV profile remains PRE_SIF-compatible;
        // the explicit fiscal-dev profile exercises the complete fiscal path.
        if (runtimeProperties != null && runtimeProperties.isSandbox()) {
            return false;
        }
        if (installationStatus.status().mode()
                != com.tpverp.backend.shared.access.OperationalMode.DEVELOPMENT) {
            return false;
        }
        // DEVELOPMENT remains PRE-SIF compatible until a real SaaS license is
        // linked. Once licensed, the same normal sale path must create the
        // fiscal record instead of silently bypassing VeriFactu.
        if (licenses == null) {
            return true;
        }
        var storeId = organization.currentStore().getId();
        var installationId = FiscalInstallationResolver.resolveCurrent(
                organization, installations, licenses).getId();
        return installationId == null
                || licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(storeId, installationId)
                        .isEmpty();
    }

    private void publishQueuedIfNeeded(com.tpverp.backend.verifactu.FiscalRecord record) {
        if (record.getFiscalMode() != com.tpverp.backend.verifactu.FiscalMode.NO_VERIFACTU) {
            events.publishEvent(new FiscalRecordQueuedEvent(record.getId()));
        }
    }
}
