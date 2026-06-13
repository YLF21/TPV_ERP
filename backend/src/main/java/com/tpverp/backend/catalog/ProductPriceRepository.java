package com.tpverp.backend.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, UUID> {

    List<ProductPrice> findByProductId(UUID productId);
}
