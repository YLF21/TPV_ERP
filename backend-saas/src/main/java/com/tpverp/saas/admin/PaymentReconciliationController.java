package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyId}/reconciliations")
public class PaymentReconciliationController {

    private final PaymentReconciliationService service;

    public PaymentReconciliationController(PaymentReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    public List<PaymentReconciliationResponse> list(@PathVariable UUID companyId) {
        return service.list(companyId);
    }

    @PostMapping
    public PaymentReconciliationResponse create(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreatePaymentReconciliationRequest request) {
        return service.create(companyId, request);
    }
}
