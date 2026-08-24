package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import org.junit.jupiter.api.Test;

class FiscalExportServiceTest {

    @Test
    void rechazaTipoDeExportacionNuloAntesDeAccederAlContextoFiscal() {
        var service = new FiscalExportService(
                mock(CurrentOrganization.class),
                mock(InstallationRepository.class),
                mock(VerifactuConfigurationRepository.class),
                mock(FiscalRecordRepository.class),
                mock(FiscalRecordArtifactRepository.class),
                mock(FiscalEventRepository.class),
                mock(FiscalEventService.class),
                mock(FiscalExportRepository.class));

        assertThatThrownBy(() -> service.export(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El tipo de exportacion es obligatorio");
    }
}
