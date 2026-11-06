package com.tpverp.backend.document;

import com.tpverp.backend.security.application.PermissionChecks;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-reports/customer-options")
public class DocumentReportCustomerOptionsController {
    private final TicketReportService tickets;
    private final DocumentReportService documents;

    public DocumentReportCustomerOptionsController(TicketReportService tickets, DocumentReportService documents) {
        this.tickets = tickets;
        this.documents = documents;
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public List<CustomerDocumentReportQueryRepository.CustomerOption> tickets(
            @RequestParam(required = false) LocalDate dateFrom, @RequestParam(required = false) LocalDate dateTo) {
        return tickets.customerOptions(dateFrom, dateTo);
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','INVOICES_READ','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public List<CustomerDocumentReportQueryRepository.CustomerOption> invoices(
            @RequestParam(required = false) LocalDate dateFrom, @RequestParam(required = false) LocalDate dateTo,
            Authentication authentication) {
        return documents.customerOptions(true, dateFrom, dateTo,
                PermissionChecks.hasSalesDocumentRead(authentication, "INVOICES_READ"));
    }

    @GetMapping("/delivery-notes")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','DELIVERY_NOTES_READ','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public List<CustomerDocumentReportQueryRepository.CustomerOption> deliveryNotes(
            @RequestParam(required = false) LocalDate dateFrom, @RequestParam(required = false) LocalDate dateTo,
            Authentication authentication) {
        return documents.customerOptions(false, dateFrom, dateTo,
                PermissionChecks.hasSalesDocumentRead(authentication, "DELIVERY_NOTES_READ"));
    }
}
