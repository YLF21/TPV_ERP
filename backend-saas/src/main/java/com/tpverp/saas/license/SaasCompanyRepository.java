package com.tpverp.saas.license;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasCompanyRepository extends JpaRepository<SaasCompany, UUID> {
}
