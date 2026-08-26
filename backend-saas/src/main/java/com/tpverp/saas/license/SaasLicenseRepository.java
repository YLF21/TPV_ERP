package com.tpverp.saas.license;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaasLicenseRepository extends JpaRepository<SaasLicense, UUID> {

    Optional<SaasLicense> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select license from SaasLicense license where license.reference = :reference")
    Optional<SaasLicense> findByReferenceForUpdate(@Param("reference") String reference);

    List<SaasLicense> findByCompany_Id(UUID companyId);

    long countByCompany_TaxpayerTypeAndStatus(
            TaxpayerType taxpayerType,
            LicenseSaasStatus status);
}
