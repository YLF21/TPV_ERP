package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductBulkEditRepository extends JpaRepository<ProductBulkEdit, UUID> {

    @EntityGraph(attributePaths = "comentarios")
    List<ProductBulkEdit> findByStoreIdOrderByActualizadoEnDesc(UUID storeId);

    @EntityGraph(attributePaths = "comentarios")
    Optional<ProductBulkEdit> findByIdAndStoreId(UUID id, UUID storeId);

    Optional<ProductBulkEdit> findTopBySerieIdOrderByNumeroVersionDesc(UUID seriesId);
}
