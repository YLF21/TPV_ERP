package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepresentativeRepository
        extends JpaRepository<SupplierRepresentative, SupplierRepresentativeId> {

    List<SupplierRepresentative> findBySupplierId(UUID supplierId);

    boolean existsByRepresentativeId(UUID representativeId);
}
