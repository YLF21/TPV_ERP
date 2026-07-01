package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncOutboxStatusServiceTest {

    @Mock
    private SyncOutboxEventRepository repository;

    @Test
    void cuentaEventosPorEstado() {
        when(repository.countByStatus(SyncOutboxStatus.PENDIENTE)).thenReturn(2L);
        when(repository.countByStatus(SyncOutboxStatus.ENVIANDO)).thenReturn(1L);
        when(repository.countByStatus(SyncOutboxStatus.ENVIADO)).thenReturn(8L);
        when(repository.countByStatus(SyncOutboxStatus.ERROR)).thenReturn(3L);

        var status = new SyncOutboxStatusService(repository).status();

        assertThat(status.pending()).isEqualTo(2);
        assertThat(status.sending()).isEqualTo(1);
        assertThat(status.sent()).isEqualTo(8);
        assertThat(status.error()).isEqualTo(3);
    }
}
