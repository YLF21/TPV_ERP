package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentMethodService {

    private final MetodoPagoRepository repository;
    private final CurrentOrganization organization;

    public PaymentMethodService(
            MetodoPagoRepository repository,
            CurrentOrganization organization) {
        this.repository = repository;
        this.organization = organization;
    }

    // Crea un método normalizado para una empresa.
    @Transactional
    public MetodoPago create(UUID companyId, String name, boolean protectedMethod) {
        requireCurrentCompany(companyId);
        return repository.save(new MetodoPago(companyId, name, protectedMethod));
    }

    @Transactional(readOnly = true)
    public List<MetodoPago> list(UUID companyId) {
        requireCurrentCompany(companyId);
        return repository.findAllByEmpresaIdOrderByNombre(companyId);
    }

    // Activa o desactiva respetando la protección de los métodos del sistema.
    @Transactional
    public MetodoPago setActive(UUID id, boolean active) {
        var method = repository.findByIdAndEmpresaId(
                        id, organization.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("método de pago no encontrado"));
        method.setActivo(active);
        return method;
    }

    private void requireCurrentCompany(UUID companyId) {
        if (!organization.currentCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("empresa no encontrada");
        }
    }
}
