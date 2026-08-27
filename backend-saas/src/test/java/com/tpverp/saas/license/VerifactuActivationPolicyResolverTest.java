package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VerifactuActivationPolicyResolverTest {

    @Test
    void aceptaUnaPoliticaPersistidaPosteriorAlPlazoDeAdaptacionSif() {
        var repository = mock(VerifactuActivationPolicyRepository.class);
        var policy = mock(VerifactuActivationPolicy.class);
        when(repository.findById(TaxpayerType.SOCIEDAD)).thenReturn(Optional.of(policy));
        when(policy.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(policy.getActivationDate()).thenReturn(LocalDate.of(2027, 1, 2));

        var resolver = new VerifactuActivationPolicyResolver(repository);

        assertThat(resolver.required(TaxpayerType.SOCIEDAD).activationDate())
                .isEqualTo(LocalDate.of(2027, 1, 2));
    }

    @ParameterizedTest
    @CsvSource({
        "SOCIEDAD,2027-01-02",
        "AUTONOMO,2027-07-02"
    })
    void aceptaFechasPosterioresParaCadaTipo(TaxpayerType taxpayerType, LocalDate date) {
        VerifactuActivationPolicy.validateActivationDate(taxpayerType, date);
    }
}
