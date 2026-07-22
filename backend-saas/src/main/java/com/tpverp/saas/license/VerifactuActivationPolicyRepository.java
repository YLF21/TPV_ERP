package com.tpverp.saas.license;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerifactuActivationPolicyRepository
        extends JpaRepository<VerifactuActivationPolicy, TaxpayerType> {
}
