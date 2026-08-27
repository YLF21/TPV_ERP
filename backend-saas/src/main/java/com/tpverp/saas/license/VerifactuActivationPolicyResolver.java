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
        VerifactuActivationPolicy policy = policies.findById(taxpayerType)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe politica VERI*FACTU para " + taxpayerType));
        try {
            VerifactuActivationPolicy.validateActivationDate(
                    policy.getTaxpayerType(), policy.getActivationDate());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "La politica VERI*FACTU persistida no es utilizable: "
                            + exception.getMessage(), exception);
        }
        return VerifactuPolicySnapshot.from(policy);
    }
}
