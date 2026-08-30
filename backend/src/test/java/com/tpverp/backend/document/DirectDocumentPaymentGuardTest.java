package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectDocumentPaymentGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"SALDO_MIEMBRO", "CREDITO_DEVOLUCION"})
    void blocksWalletMethodsWithStableCodeBeforeDocumentEffects(String methodName) {
        var method = new PaymentMethod(UUID.randomUUID(), methodName, true);

        assertThatThrownBy(() -> DirectDocumentPaymentGuard.requireAllowed(method))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("direct_document_payment_method_not_allowed");
    }
}
