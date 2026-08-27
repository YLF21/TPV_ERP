package com.tpverp.saas.fiscal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasFiscalStatusRepository extends JpaRepository<SaasFiscalStatus, UUID> {
    Optional<SaasFiscalStatus> findByInstallation_Id(UUID installationId);
    List<SaasFiscalStatus> findAllByOrderByCompany_NameAscStore_NameAsc();
    List<SaasFiscalStatus> findByCompany_IdOrderByStore_NameAsc(UUID companyId);
}
