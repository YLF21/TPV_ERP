package com.tpverp.backend.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    List<Store> findByEmpresaId(UUID empresaId);

    boolean existsByEmpresaIdAndCodigoTienda(UUID empresaId, String codigoTienda);

    Optional<Store> findByEmpresaIdAndAddressNormalizedHash(UUID empresaId, String addressNormalizedHash);
}
