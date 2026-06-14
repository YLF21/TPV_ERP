package com.tpverp.backend.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TiendaRepository extends JpaRepository<Tienda, UUID> {

    List<Tienda> findByEmpresaId(UUID empresaId);

    boolean existsByEmpresaIdAndCodigoFiscal(UUID empresaId, String codigoFiscal);

    Optional<Tienda> findByEmpresaIdAndAddressNormalizedHash(UUID empresaId, String addressNormalizedHash);
}
