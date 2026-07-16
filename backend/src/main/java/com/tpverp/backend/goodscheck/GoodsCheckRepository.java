package com.tpverp.backend.goodscheck;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsCheckRepository extends JpaRepository<GoodsCheck, UUID> {

    boolean existsByDocumentoIdAndEstado(UUID documentId, GoodsCheckStatus status);

    Optional<GoodsCheck> findByIdAndTiendaId(UUID id, UUID storeId);

    Optional<GoodsCheck> findByDocumentoIdAndEstadoAndTiendaId(
            UUID documentId, GoodsCheckStatus status, UUID storeId);
}
