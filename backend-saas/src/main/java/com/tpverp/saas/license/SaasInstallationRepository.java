package com.tpverp.saas.license;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasInstallationRepository extends JpaRepository<SaasInstallation, UUID> {

    Optional<SaasInstallation> findByInstallationIdAndLicense_Reference(UUID installationId, String licenseReference);

    List<SaasInstallation> findByCompany_Id(UUID companyId);

    List<SaasInstallation> findAllByOrderByLinkedAtDesc();

    long countByCompany_TaxpayerType(TaxpayerType taxpayerType);
}
