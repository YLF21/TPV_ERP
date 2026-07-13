package com.tpverp.backend.document;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    List<PaymentMethod> findAllByEmpresaIdOrderByNombre(UUID empresaId);

    Optional<PaymentMethod> findByIdAndEmpresaId(UUID id, UUID empresaId);

    Optional<PaymentMethod> findByEmpresaIdAndNombreAndActivoTrue(UUID companyId, String name);
}
