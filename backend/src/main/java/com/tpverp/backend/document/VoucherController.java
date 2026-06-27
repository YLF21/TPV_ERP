package com.tpverp.backend.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vouchers")
public class VoucherController {

    private final VoucherService vouchers;
    private final CommercialDocumentRepository documents;

    public VoucherController(VoucherService vouchers, CommercialDocumentRepository documents) {
        this.vouchers = vouchers;
        this.documents = documents;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public List<VoucherView> list() {
        return vouchers.list().stream().map(VoucherView::from).toList();
    }

    @PostMapping("/issue-from-ticket/{ticketId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherView issueFromTicket(@PathVariable UUID ticketId) {
        return VoucherView.from(vouchers.issueFromNegativeTicket(document(ticketId)));
    }

    @PostMapping("/{code}/consume")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherConsumptionView consume(
            @PathVariable String code,
            @RequestBody ConsumeVoucherRequest request) {
        return VoucherConsumptionView.from(vouchers.consume(
                code, request.pendingAmount(), document(request.ticketId())));
    }

    private CommercialDocument document(UUID id) {
        var document = documents.findById(id);
        if (document.isEmpty()) {
            throw new IllegalArgumentException("documento no encontrado");
        }
        return document.get();
    }

    public record ConsumeVoucherRequest(
            @NotNull UUID ticketId,
            @NotNull BigDecimal pendingAmount,
            @NotBlank String reason) {
    }
}
