package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FiscalIntegrityServiceTest {

    @Test
    void detectaAlteracionDeSnapshotYXmlDeEventoComoAnomaliaBloqueante() {
        var company = new Company("B00000000", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var installation = mock(Installation.class);
        when(installation.getId()).thenReturn(java.util.UUID.randomUUID());
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.changeMode(FiscalMode.NO_VERIFACTU, Instant.now(), null);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));

        var record = mock(FiscalRecord.class);
        when(record.getSequence()).thenReturn(1L);
        when(record.getSnapshot()).thenReturn(Map.of("total", new BigDecimal("12.10")));
        when(record.getSnapshotHash()).thenReturn("0".repeat(64));
        var records = mock(FiscalRecordRepository.class);
        when(records.findAllByCompanyIdAndInstallationIdOrderBySequence(
                company.getId(), installation.getId())).thenReturn(List.of(record));

        var event = mock(FiscalEvent.class);
        when(event.getSequence()).thenReturn(2L);
        when(event.getSignedXml()).thenReturn("<RegistroEvento/>");
        when(event.getXmlHash()).thenReturn("F".repeat(64));
        var events = mock(FiscalEventRepository.class);
        when(events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                company.getId(), installation.getId())).thenReturn(List.of(event));
        var alarms = mock(FiscalAlarmRepository.class);
        var eventService = mock(FiscalEventService.class);
        var service = new FiscalIntegrityService(organization, installations, configurations,
                records, events, alarms, eventService);

        var result = service.check();

        assertThat(result.ok()).isFalse();
        assertThat(result.anomalies()).containsExactlyInAnyOrder(
                "INTEGRIDAD_SNAPSHOT_1", "INTEGRIDAD_XML_EVENTO_2");
        verify(alarms, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.any(FiscalAlarm.class));
    }
}
