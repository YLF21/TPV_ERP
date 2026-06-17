package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerifactuSignaturePolicyTest {

    @Test
    void noExigeFirmaElectronicaEnModalidadVerifactu() {
        var policy = new VerifactuSignaturePolicy();

        assertThat(policy.requiredForVerifactu()).isFalse();
        assertThat(policy.mode()).isEqualTo("NOT_REQUIRED_FOR_VERIFACTU");
    }
}
