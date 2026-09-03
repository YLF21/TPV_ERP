package com.tpverp.backend.document;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import com.tpverp.backend.organization.CurrentOrganization;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vouchers")
public class VoucherController {

    private final VoucherService vouchers;
    private final CommercialDocumentRepository documents;
    private final CurrentOrganization organization;
    private final RefundTenderRepository refundTenders;
    private final VoucherManagementService management;
    private final Clock clock;

    public VoucherController(VoucherService vouchers, CommercialDocumentRepository documents,
            CurrentOrganization organization, RefundTenderRepository refundTenders,
            VoucherManagementService management, Clock clock) {
        this.vouchers = vouchers;
        this.documents = documents;
        this.organization = organization;
        this.refundTenders = refundTenders;
        this.management = management;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public List<VoucherView> list() {
        var today = today();
        var result = new java.util.ArrayList<VoucherView>();
        for (var voucher : vouchers.list()) {
            result.add(VoucherView.from(voucher, today));
        }
        return List.copyOf(result);
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public ResponseEntity<VoucherView> findByCode(@PathVariable String code) {
        var voucher = vouchers.findByCode(code);
        if (voucher.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(VoucherView.from(voucher.orElseThrow(), today()));
    }

    @PostMapping("/issue-from-ticket/{ticketId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherView issueFromTicket(@PathVariable UUID ticketId) {
        var ticket = document(ticketId);
        var existing = vouchers.issuedFromNegativeTicket(ticket);
        if (existing.isPresent()) {
            return VoucherView.from(existing.orElseThrow(), today());
        }
        var amount = BigDecimal.ZERO;
        for (var tender : refundTenders.findByRefundDocumentIdOrderByCreatedAtAsc(ticketId)) {
            if (tender.getType() == RefundTenderType.VOUCHER) {
                amount = amount.add(tender.getAmount());
            }
        }
        if (amount.signum() <= 0) {
            throw new IllegalStateException("la devolucion no fue configurada como reembolso mediante vale");
        }
        return VoucherView.from(
                vouchers.issueOrFindFromNegativeTicket(ticket, amount), today());
    }

    @PostMapping("/{code}/consume")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherConsumptionView consume(
            @PathVariable String code,
            @RequestBody ConsumeVoucherRequest request) {
        return VoucherConsumptionView.from(vouchers.consume(
                code, request.pendingAmount(), document(request.ticketId())), today());
    }

    @GetMapping("/management")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherManagementService.ManagementPage managementList(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) VoucherEffectiveStatus status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return management.list(query, status, from, to, page, size);
    }

    @GetMapping("/{code}/management")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherManagementService.Detail managementDetail(@PathVariable String code) {
        return management.detail(code);
    }

    @GetMapping("/configuration")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    @RequireGestionGroup(GestionGroup.CONFIGURACION)
    public VoucherManagementService.ConfigurationView configuration() {
        return management.configuration();
    }

    @org.springframework.web.bind.annotation.PutMapping("/configuration")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireGestionGroup(GestionGroup.CONFIGURACION)
    public VoucherManagementService.ConfigurationView updateConfiguration(
            @RequestBody @jakarta.validation.Valid VoucherConfigurationRequest request,
            org.springframework.security.core.Authentication authentication) {
        return management.updateConfiguration(
                request.expirationMode(), request.validityDays(), authentication);
    }

    @PostMapping("/{code}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public VoucherManagementService.Detail reactivate(
            @PathVariable String code,
            @RequestBody @jakarta.validation.Valid ReactivateVoucherRequest request,
            org.springframework.security.core.Authentication authentication) {
        return management.reactivate(code, request.expiresOn(), request.reason(), authentication);
    }

    @GetMapping("/{code}/print-document")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherPrintService.PrintedVoucher printDocument(@PathVariable String code) {
        return management.printDocument(code);
    }

    @PostMapping("/{code}/print-events")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VoucherManagementService.Detail recordPrintResult(
            @PathVariable String code,
            @RequestBody @jakarta.validation.Valid PrintResultRequest request,
            org.springframework.security.core.Authentication authentication) {
        return management.recordPrintResult(code, request.success(), authentication);
    }

    private CommercialDocument document(UUID id) {
        var document = documents.findByIdAndTiendaId(id, organization.currentStore().getId());
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

    private LocalDate today() {
        return clock.instant()
                .atZone(ZoneId.of(organization.currentStore().getTimezone()))
                .toLocalDate();
    }

    public record VoucherConfigurationRequest(
            @NotNull VoucherExpirationMode expirationMode,
            @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(StoreVoucherConfiguration.MAX_VALIDITY_DAYS)
            int validityDays) {
    }

    public record ReactivateVoucherRequest(
            @NotNull LocalDate expiresOn,
            @NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record PrintResultRequest(boolean success) {
    }
}
