package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesRepresentativeRepository
        extends JpaRepository<SalesRepresentative, UUID> {

    List<SalesRepresentative> findByCompanyIdOrderByNombre(UUID companyId);

    java.util.Optional<SalesRepresentative> findByIdAndCompanyId(UUID id, UUID companyId);
}
