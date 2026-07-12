package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceRuleRepository extends JpaRepository<ProductPriceRule, UUID> {

    List<ProductPriceRule> findByCompanyIdOrderByUpdatedAtDesc(UUID companyId);

    Optional<ProductPriceRule> findByIdAndCompanyId(UUID id, UUID companyId);
}
