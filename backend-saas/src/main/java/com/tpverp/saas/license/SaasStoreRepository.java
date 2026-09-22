package com.tpverp.saas.license;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasStoreRepository extends JpaRepository<SaasStore, UUID> {

    List<SaasStore> findByCompany_IdOrderByCodeAsc(UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select store from SaasStore store where store.id = :id")
    Optional<SaasStore> findByIdForUpdate(@Param("id") UUID id);
}
