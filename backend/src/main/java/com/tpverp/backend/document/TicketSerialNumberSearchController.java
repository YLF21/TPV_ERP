package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/tickets/serial-number-search")
public class TicketSerialNumberSearchController {
    private static final int PAGE_SIZE = 50;
    private final CommercialDocumentRepository documents;
    private final CurrentOrganization organization;

    public TicketSerialNumberSearchController(CommercialDocumentRepository documents,
            CurrentOrganization organization) {
        this.documents = documents;
        this.organization = organization;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA','INVOICES_READ')")
    public Result search(@RequestParam(defaultValue = "") @Size(max = 128) String serialNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "date") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection) {
        var normalized = serialNumber.trim().toUpperCase(Locale.ROOT);
        if (page < 0 || serialNumber.length() > 128) {
            throw new IllegalArgumentException("invalid_serial_number_search");
        }
        if (!java.util.Set.of("number", "date", "productCode", "productName", "serialNumber", "total", "status").contains(sortBy)
                || !("asc".equals(sortDirection) || "desc".equals(sortDirection))) {
            throw new IllegalArgumentException("invalid_serial_number_sort");
        }
        var sort = Sort.by(Sort.Direction.fromString(sortDirection), sortBy)
                .and(Sort.by(Sort.Direction.DESC, "ticketId"))
                .and(Sort.by("lineId", "serialNumber"));
        var matches = documents.findTicketsBySerialNumber(organization.currentStore().getId(),
                normalized, PageRequest.of(page, PAGE_SIZE, sort));
        return new Result(matches.getContent().stream().map(row -> new Match(
                row.getTicketId(), row.getLineId(), row.getNumber(), row.getDate(), row.getStatus(),
                row.getTotal(), row.getProductCode(), row.getProductName(), row.getSerialNumber())).toList(),
                matches.hasNext() ? Math.addExact(page, 1) : null);
    }

    public record Result(List<Match> items, Integer nextPage) { }
    public record Match(UUID ticketId, UUID lineId, String number, LocalDate date,
            DocumentStatus status, BigDecimal total, String productCode, String productName,
            String serialNumber) { }
}
