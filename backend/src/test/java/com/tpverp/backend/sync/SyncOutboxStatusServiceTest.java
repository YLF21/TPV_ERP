package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncOutboxStatusServiceTest {

    @Mock private SyncOutboxEventRepository repository;
    @Mock private CurrentOrganization organization;
    @Mock private Company company;
    @Mock private Store store;

    @Test
    void cuentaEventosPorEstadoSoloParaLaTiendaActual() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(repository.countForStore(companyId, storeId, SyncOutboxStatus.PENDIENTE)).thenReturn(2L);
        when(repository.countForStore(companyId, storeId, SyncOutboxStatus.ENVIANDO)).thenReturn(1L);
        when(repository.countForStore(companyId, storeId, SyncOutboxStatus.ENVIADO)).thenReturn(8L);
        when(repository.countForStore(companyId, storeId, SyncOutboxStatus.ERROR)).thenReturn(3L);
        when(repository.countForStore(companyId, storeId, SyncOutboxStatus.DEAD_LETTER)).thenReturn(4L);

        var status = new SyncOutboxStatusService(repository, organization).status();

        assertThat(status.pending()).isEqualTo(2);
        assertThat(status.sending()).isEqualTo(1);
        assertThat(status.sent()).isEqualTo(8);
        assertThat(status.error()).isEqualTo(3);
        assertThat(status.deadLetter()).isEqualTo(4);
    }
}
