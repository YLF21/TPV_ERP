package com.tpverp.backend.document;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentMethodService {

    private final MetodoPagoRepository repository;

    public PaymentMethodService(MetodoPagoRepository repository) {
        this.repository = repository;
    }

    // Crea un método normalizado para una empresa.
    @Transactional
    public MetodoPago create(UUID companyId, String name, boolean protectedMethod) {
        return repository.save(new MetodoPago(companyId, name, protectedMethod));
    }

    @Transactional(readOnly = true)
    public List<MetodoPago> list(UUID companyId) {
        return repository.findAllByEmpresaIdOrderByNombre(companyId);
    }

    // Activa o desactiva respetando la protección de los métodos del sistema.
    @Transactional
    public MetodoPago setActive(UUID id, boolean active) {
        var method = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("método de pago no encontrado"));
        method.setActivo(active);
        return method;
    }
}
