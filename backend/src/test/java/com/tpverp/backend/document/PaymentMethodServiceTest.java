package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentMethodServiceTest {

    @Mock
    private MetodoPagoRepository repository;

    @Test
    void protectedMethodCannotBeDisabled() {
        var method = new MetodoPago(UUID.randomUUID(), "EFECTIVO", true);
        when(repository.findById(method.getId())).thenReturn(Optional.of(method));

        assertThatThrownBy(() -> new PaymentMethodService(repository)
                .setActive(method.getId(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protegido");
    }
}
