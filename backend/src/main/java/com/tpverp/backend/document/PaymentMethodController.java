package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-methods")
public class PaymentMethodController {

    private final PaymentMethodService service;

    public PaymentMethodController(PaymentMethodService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_CREATE','INVOICES_WRITE')")
    public List<PaymentMethodView> list(@RequestParam UUID companyId) {
        return service.list(companyId).stream().map(PaymentMethodView::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentMethodView create(@Valid @RequestBody CreatePaymentMethodRequest request) {
        return PaymentMethodView.from(service.create(
                request.companyId(), request.name(), request.protectedMethod(),
                request.requiresReference(), request.opensCashDrawer()));
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentMethodView setActive(
            @PathVariable UUID id,
            @Valid @RequestBody ActiveRequest request) {
        return PaymentMethodView.from(service.setActive(id, request.active()));
    }

    @PatchMapping("/{id}/configuration")
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentMethodView configure(
            @PathVariable UUID id,
            @Valid @RequestBody ConfigureRequest request) {
        return PaymentMethodView.from(service.configure(
                id, request.requiresReference(), request.opensCashDrawer()));
    }

    public record CreatePaymentMethodRequest(
            @NotNull UUID companyId,
            @NotBlank String name,
            boolean protectedMethod,
            boolean requiresReference,
            boolean opensCashDrawer) {
    }

    public record ActiveRequest(boolean active) {
    }

    public record ConfigureRequest(
            boolean requiresReference,
            boolean opensCashDrawer) {
    }

    public record PaymentMethodView(
            UUID id,
            UUID companyId,
            String name,
            boolean protectedMethod,
            boolean active,
            boolean requiresReference,
            boolean opensCashDrawer) {

        static PaymentMethodView from(PaymentMethod method) {
            return new PaymentMethodView(
                    method.getId(), method.getEmpresaId(), method.getNombre(),
                    method.isProtegido(), method.isActivo(),
                    method.isRequiereReferencia(), method.isAbreCajaRegistradora());
        }
    }
}
