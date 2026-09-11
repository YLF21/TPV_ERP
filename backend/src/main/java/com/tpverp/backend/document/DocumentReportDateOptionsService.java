package com.tpverp.backend.document;

import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseOutputRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentReportDateOptionsService {

    private final CommercialDocumentRepository documents;
    private final WarehouseInputRepository inputs;
    private final WarehouseOutputRepository outputs;
    private final CurrentOrganization organization;
    private final Clock clock;

    public DocumentReportDateOptionsService(CommercialDocumentRepository documents,
            WarehouseInputRepository inputs, WarehouseOutputRepository outputs,
            CurrentOrganization organization, Clock clock) {
        this.documents = documents;
        this.inputs = inputs;
        this.outputs = outputs;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DateOptions options(String report, Authentication authentication) {
        var allowed = switch (report) {
            case "tickets" -> PermissionChecks.hasSalesDocumentRead(authentication, "TICKETS_READ");
            case "invoices" -> PermissionChecks.hasSalesDocumentRead(authentication, "INVOICES_READ");
            case "deliveryNotes" -> PermissionChecks.hasSalesDocumentRead(authentication, "DELIVERY_NOTES_READ");
            case "inputInvoices", "inputDeliveryNotes" -> PermissionChecks.hasPurchaseDocumentRead(authentication);
            case "warehouseOutputs", "inputWarehouse" -> PermissionChecks.hasWarehouseManagement(authentication);
            default -> throw new IllegalArgumentException("Informe de documentos no valido");
        };
        if (!allowed) throw new AccessDeniedException("Sin permiso para consultar este informe");
        var store = organization.currentStore();
        var current = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        LocalDate earliest = switch (report) {
            case "tickets" -> documents.findFirstReportDate(store.getId(), EnumSet.of(CommercialDocumentType.TICKET));
            case "invoices" -> documents.findFirstReportDate(store.getId(), EnumSet.of(
                    CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA));
            case "deliveryNotes" -> documents.findFirstReportDate(store.getId(), EnumSet.of(CommercialDocumentType.ALBARAN_VENTA));
            case "inputInvoices" -> inputs.findFirstReportDate(store.getId(), WarehouseInputDocumentType.FACTURA_ENTRADA);
            case "inputDeliveryNotes" -> inputs.findFirstReportDate(store.getId(), WarehouseInputDocumentType.ALBARAN_ENTRADA);
            case "warehouseOutputs" -> outputs.findFirstReportDate(store.getId());
            case "inputWarehouse" -> inputs.findFirstReportDate(store.getId(), WarehouseInputDocumentType.ENTRADA_ALMACEN);
            default -> throw new IllegalArgumentException("Informe de documentos no valido");
        };
        return new DateOptions(earliest == null || earliest.isAfter(current) ? current : earliest, current);
    }

    public record DateOptions(LocalDate earliestDate, LocalDate currentDate) { }
}
