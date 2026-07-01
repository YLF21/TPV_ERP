package com.tpverp.saas.license;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasStoreRepository extends JpaRepository<SaasStore, UUID> {

    List<SaasStore> findByCompany_IdOrderByCodeAsc(UUID companyId);
}
