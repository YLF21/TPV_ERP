package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreTaxRepository extends JpaRepository<StoreTax, UUID> {

    List<StoreTax> findByStoreIdOrderByPorcentaje(UUID storeId);

    Optional<StoreTax> findByStoreIdAndPorcentaje(UUID storeId, BigDecimal percentage);

    Optional<StoreTax> findByStoreIdAndPredeterminadoTrue(UUID storeId);
}
