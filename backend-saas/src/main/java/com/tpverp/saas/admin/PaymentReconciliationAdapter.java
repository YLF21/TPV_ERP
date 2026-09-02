package com.tpverp.saas.admin;

public interface PaymentReconciliationAdapter {
    boolean supports(String provider);

    void validate(CreatePaymentReconciliationRequest request);
}
