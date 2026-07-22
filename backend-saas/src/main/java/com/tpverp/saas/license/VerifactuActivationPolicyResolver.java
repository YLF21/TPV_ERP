package com.tpverp.saas.license;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuActivationPolicyResolver {

    private final VerifactuActivationPolicyRepository policies;

    public VerifactuActivationPolicyResolver(VerifactuActivationPolicyRepository policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public VerifactuPolicySnapshot required(TaxpayerType taxpayerType) {
        return policies.findById(taxpayerType)
                .map(VerifactuPolicySnapshot::from)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe politica VERI*FACTU para " + taxpayerType));
    }
}
