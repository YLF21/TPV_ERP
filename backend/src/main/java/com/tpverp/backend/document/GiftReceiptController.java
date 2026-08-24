package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.tpverp.backend.document.template.RenderedDocumentView;

@RestController
@RequestMapping("/api/v1/gift-receipts")
public class GiftReceiptController {

    private final GiftReceiptService service;

    public GiftReceiptController(GiftReceiptService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public GiftReceiptService.Preview preview(@RequestParam String ticketNumber) {
        return service.preview(ticketNumber);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_CREATE','VENTA')")
    public GiftReceiptService.View issue(
            @Valid @RequestBody Request request,
            Authentication authentication) {
        return service.issue(
                request.requestId(),
                request.ticketNumber(),
                request.lines().stream()
                        .map(line -> new GiftReceiptService.LineSelection(
                                line.lineId(), line.quantity(), line.serialNumbers()))
                        .toList(),
                authentication);
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public GiftReceiptService.View find(@PathVariable String code) {
        return service.findByCode(code);
    }

    @GetMapping("/{code}/print-document")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public RenderedDocumentView printDocument(@PathVariable String code) {
        return service.printDocument(code);
    }

    public record Request(
            @NotNull UUID requestId,
            @NotBlank String ticketNumber,
            @NotEmpty List<@Valid Line> lines) {
    }

    public record Line(
            @NotNull UUID lineId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
            List<String> serialNumbers) {
        public Line {
            serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
        }
    }
}
