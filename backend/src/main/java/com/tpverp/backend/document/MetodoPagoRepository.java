package com.tpverp.backend.document;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetodoPagoRepository extends JpaRepository<MetodoPago, UUID> {

    List<MetodoPago> findAllByEmpresaIdOrderByNombre(UUID empresaId);
}
