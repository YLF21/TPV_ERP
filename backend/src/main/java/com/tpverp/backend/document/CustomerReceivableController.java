package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentTerminalResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer-receivables")
public class CustomerReceivableController {

    private static final String READ_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('CUSTOMER_RECEIVABLES_READ')";
    private static final String PAY_PERMISSION =
            "hasRole('ADMIN') or hasAnyAuthority('CUSTOMER_RECEIVABLES_PAY','GESTION_VENTAS','VENTA')";

    private final CustomerReceivableService service;

    public CustomerReceivableController(CustomerReceivableService service) {
        this.service = service;
    }

    @GetMapping("")
    @PreAuthorize(READ_PERMISSION)
    public List<CustomerReceivableView> list(
            @Valid @ModelAttribute CustomerReceivableFilter filter,
            Authentication authentication) {
        return service.list(filter, authentication);
    }

    @GetMapping("/{documentId}")
    @PreAuthorize(READ_PERMISSION)
    public CustomerReceivableView detail(
            @PathVariable UUID documentId, Authentication authentication) {
        return service.detail(documentId, authentication);
    }

    @PostMapping("/{documentId}/card-charges")
    @PreAuthorize(PAY_PERMISSION)
    public PaymentTerminalResult chargeCard(
            @PathVariable UUID documentId,
            @Valid @RequestBody CardChargeRequest request,
            Authentication authentication) {
        return service.chargeCard(documentId, request, authentication);
    }

    @PostMapping("/{documentId}/payments")
    @PreAuthorize(PAY_PERMISSION)
    public CustomerReceivableView pay(
            @PathVariable UUID documentId,
            @Valid @RequestBody PaymentRequest request,
            Authentication authentication) {
        return service.pay(documentId, request, authentication);
    }

    public record CardChargeRequest(
            @NotNull UUID paymentId,
            @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }
}
