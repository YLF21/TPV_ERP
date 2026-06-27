package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentMethodService {

    private final PaymentMethodRepository repository;
    private final CurrentOrganization organization;

    public PaymentMethodService(
            PaymentMethodRepository repository,
            CurrentOrganization organization) {
        this.repository = repository;
        this.organization = organization;
    }

    // Creates a normalized method for a company.
    @Transactional
    public PaymentMethod create(UUID companyId, String name, boolean protectedMethod) {
        requireCurrentCompany(companyId);
        return repository.save(new PaymentMethod(companyId, name, protectedMethod));
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> list(UUID companyId) {
        requireCurrentCompany(companyId);
        return repository.findAllByEmpresaIdOrderByNombre(companyId);
    }

    // Activates or deactivates while respecting protected system methods.
    @Transactional
    public PaymentMethod setActive(UUID id, boolean active) {
        var method = repository.findByIdAndEmpresaId(
                        id, organization.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.payment_method.not_found"));
        method.setActivo(active);
        return method;
    }

    private void requireCurrentCompany(UUID companyId) {
        if (!organization.currentCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("empresa no encontrada");
        }
    }
}
