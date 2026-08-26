package com.tpverp.saas.license;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SaasInstallationRepository extends JpaRepository<SaasInstallation, UUID> {

    Optional<SaasInstallation> findByInstallationId(UUID installationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select installation from SaasInstallation installation "
            + "where installation.installationId = :installationId")
    Optional<SaasInstallation> findByInstallationIdForUpdate(
            @Param("installationId") UUID installationId);

    Optional<SaasInstallation> findByInstallationIdAndLicense_Reference(UUID installationId, String licenseReference);

    List<SaasInstallation> findByCompany_Id(UUID companyId);

    List<SaasInstallation> findByCompany_IdAndActiveTrue(UUID companyId);

    List<SaasInstallation> findByCompany_IdAndStore_Id(UUID companyId, UUID storeId);

    boolean existsByCompany_IdAndStore_Id(UUID companyId, UUID storeId);

    boolean existsByStore_IdAndActiveTrue(UUID storeId);

    List<SaasInstallation> findAllByOrderByLinkedAtDesc();

    long countByCompany_TaxpayerType(TaxpayerType taxpayerType);

    long countByCompany_TaxpayerTypeAndActiveTrue(TaxpayerType taxpayerType);

    long countByActiveTrue();
}
