package com.tpverp.saas.license;

import java.time.Instant;
import java.time.LocalDate;

public record VerifactuPolicySnapshot(
        LocalDate activationDate,
        long version,
        Instant updatedAt) {

    public static VerifactuPolicySnapshot from(VerifactuActivationPolicy policy) {
        return new VerifactuPolicySnapshot(
                policy.getActivationDate(), policy.getVersion(), policy.getUpdatedAt());
    }
}
