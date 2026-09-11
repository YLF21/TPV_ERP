package com.tpverp.backend.document;

import com.tpverp.backend.shared.api.PagedResult;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-reports/tickets")
public class TicketReportController {

    private final TicketReportService service;

    public TicketReportController(TicketReportService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public PagedResult<TicketReportView> tickets(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String customerId,
            @ModelAttribute CustomerDocumentReportFilter filter) {
        return service.list(limit, cursor,
                customerId == null ? null : UUID.fromString(customerId), filter);
    }
}
