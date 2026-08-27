package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class FiscalModeTransitionSchedulerTest {

    @Test
    void aplicaUnaTransicionVencidaYGeneraEvento01() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var transitions = mock(FiscalModeTransitionRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var events = mock(FiscalEventService.class);
                var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                  .withProperty("tpv.verifactu.runtime-class", "REAL")
                  .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                  .withProperty("tpv.verifactu.transport-mode", "AEAT")
                  .withProperty("tpv.verifactu.aeat-test-network-enabled", "true"));
        var configuration = new VerifactuConfiguration(companyId);
        configuration.changeMode(FiscalMode.VERIFACTU, Instant.parse("2027-01-01T00:00:00Z"), null);
        var scheduled = new FiscalModeTransition(companyId, installationId,
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU,
                Instant.parse("2027-01-02T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), "ADMIN", "fin", 1,
                LocalDate.of(2027, 1, 31), "ACK-1");
        when(transitions.findDueWithoutAppliedTransition(
                FiscalModeTransitionStatus.PROGRAMADA, FiscalModeTransitionStatus.APLICADA,
                FiscalModeTransitionStatus.FALLIDA,
                Instant.parse("2027-02-02T00:00:00Z"))).thenReturn(List.of(scheduled));
        when(transitions.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        when(configurations.findForUpdateByCompanyId(companyId)).thenReturn(Optional.of(configuration));

        var scheduler = new FiscalModeTransitionScheduler(transitions, configurations, events, runtime);
        configureLicensePolicy(scheduler, companyId, installationId,
                LocalDate.of(2028, 1, 1));

        assertThat(scheduler.applyDue(Instant.parse("2027-02-02T00:00:00Z"))).isEqualTo(1);
        assertThat(configuration.getCurrentMode()).isEqualTo(FiscalMode.NO_VERIFACTU);
        verify(events).create(eq(companyId), eq(installationId), eq(FiscalMode.NO_VERIFACTU),
                eq(FiscalEventType.START_NO_VERIFACTU), eq("Salida VERI*FACTU; ACK ACK-1"));
    }

    @Test
    void revalidaYBloqueaLaSalidaSiLaLicenciaAhoraObligaVerifactu() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var transitions = mock(FiscalModeTransitionRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var events = mock(FiscalEventService.class);
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT")
                .withProperty("tpv.verifactu.aeat-test-network-enabled", "true"));
        var configuration = new VerifactuConfiguration(companyId);
        configuration.changeMode(FiscalMode.VERIFACTU,
                Instant.parse("2026-01-01T00:00:00Z"), null);
        var scheduled = new FiscalModeTransition(companyId, installationId,
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU,
                Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), "ADMIN", "fin", 1,
                LocalDate.of(2027, 1, 31), "ACK-2");
        var now = Instant.parse("2027-02-02T00:00:00Z");
        when(transitions.findDueWithoutAppliedTransition(
                FiscalModeTransitionStatus.PROGRAMADA, FiscalModeTransitionStatus.APLICADA,
                FiscalModeTransitionStatus.FALLIDA, now))
                .thenReturn(List.of(scheduled));
        when(transitions.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        when(configurations.findForUpdateByCompanyId(companyId))
                .thenReturn(Optional.of(configuration));
        var scheduler = new FiscalModeTransitionScheduler(
                transitions, configurations, events, runtime);
        configureLicensePolicy(scheduler, companyId, installationId,
                LocalDate.of(2027, 1, 1));

        assertThat(scheduler.applyDue(now)).isZero();
        assertThat(configuration.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        verify(configurations, never()).save(configuration);
        var persisted = ArgumentCaptor.forClass(FiscalModeTransition.class);
        verify(transitions).save(persisted.capture());
        assertThat(persisted.getValue().getStatus())
                .isEqualTo(FiscalModeTransitionStatus.FALLIDA);
        assertThat(persisted.getValue().getSourceTransitionId()).isEqualTo(scheduled.getId());
        assertThat(persisted.getValue().getLastErrorCode())
                .isEqualTo("TRANSITION_APPLICATION_FAILED");
        assertThat(persisted.getValue().getLastError())
                .contains("licencia obliga VERI*FACTU");
    }

    @Test
    void segundoWorkerDetectaLaAplicacionTrasElLockYNoRegistraUnFallo() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var transitions = mock(FiscalModeTransitionRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var events = mock(FiscalEventService.class);
        var scheduled = new FiscalModeTransition(companyId, installationId,
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU,
                Instant.parse("2027-01-02T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), "ADMIN", "fin", 1,
                LocalDate.of(2027, 1, 31), "ACK-RACE");
        var configurationAlreadyApplied = new VerifactuConfiguration(companyId);
        configurationAlreadyApplied.changeMode(
                FiscalMode.VERIFACTU, Instant.parse("2027-01-01T00:00:00Z"), null);
        configurationAlreadyApplied.changeMode(
                FiscalMode.NO_VERIFACTU, Instant.parse("2027-02-02T00:00:00Z"), null);
        when(transitions.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        when(configurations.findForUpdateByCompanyId(companyId))
                .thenReturn(Optional.of(configurationAlreadyApplied));
        when(transitions.countAppliedTransitionsForSchedule(
                companyId, installationId, FiscalModeTransitionStatus.APLICADA,
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU, 1,
                scheduled.getEffectiveAt())).thenReturn(1L);
        var executor = new FiscalModeTransitionExecutor(transitions, configurations, events);

        assertThat(executor.apply(
                scheduled.getId(), Instant.parse("2027-02-02T00:00:00Z"))).isFalse();

        verify(configurations, never()).save(configurationAlreadyApplied);
        verify(transitions, never()).save(org.mockito.ArgumentMatchers.any());
        verify(events, never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static void configureLicensePolicy(FiscalModeTransitionScheduler scheduler,
            UUID companyId, UUID installationId, LocalDate activationDate) {
        var licenses = mock(LicenseRepository.class);
        var stores = mock(StoreRepository.class);
        var license = mock(License.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var storeId = UUID.randomUUID();
        when(license.getTiendaId()).thenReturn(storeId);
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(activationDate);
        when(licenses.findActiveByCompanyIdAndInstallationId(companyId, installationId))
                .thenReturn(List.of(license));
        when(stores.findWithCompanyById(storeId)).thenReturn(Optional.of(store));
        when(store.getEmpresa()).thenReturn(company);
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(company.getId()).thenReturn(companyId);
        scheduler.setLicensePolicy(licenses, stores, new VerifactuActivationService());
    }
}
