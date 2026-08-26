package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class FiscalModeTransitionServiceTest {

    @Test
    void noPermiteIniciarNoVerifactuConAnomalias() {
        var fixture = fixture();
        when(fixture.integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.now(), FiscalMode.PRE_SIF, false,
                List.of("CADENA_FACTURACION_2"), 2, 0));

        assertThatThrownBy(() -> fixture.service.transition(
                FiscalMode.NO_VERIFACTU, 0, "preflight", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CADENA_FACTURACION_2");
    }

    @Test
    void exigePreflightAntesDeCrearEvento01() {
        var fixture = fixture();
        when(fixture.integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.now(), FiscalMode.PRE_SIF, true, List.of(), 0, 0));

        fixture.service.transition(FiscalMode.NO_VERIFACTU, 0, "inicio", true);

        verify(fixture.events).create(
                eq(fixture.company.getId()), eq(fixture.installationId),
                eq(FiscalMode.NO_VERIFACTU), eq(FiscalEventType.START_NO_VERIFACTU), eq("inicio"));
    }

    @Test
    void ejecutaElPreflightAntesDeBloquearLaConfiguracion() {
        var fixture = fixture();
        when(fixture.integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.now(), FiscalMode.PRE_SIF, true, List.of(), 0, 0));

        fixture.service.transition(FiscalMode.NO_VERIFACTU, 0, "inicio", true);

        var order = inOrder(fixture.configurations, fixture.integrity);
        order.verify(fixture.configurations).findByCompanyId(fixture.company.getId());
        order.verify(fixture.integrity).check();
        order.verify(fixture.configurations).findForUpdateByCompanyId(fixture.company.getId());
    }

    @Test
    void conservaModoInicialSandboxAlCrearConfiguracionPersistente() {
        var fixture = fixtureWithoutConfiguration(FiscalMode.VERIFACTU);

        fixture.service.transition(FiscalMode.NO_VERIFACTU, 0, "salida laboratorio", true);

        verify(fixture.configurations).insertIfMissingWithMode(
                any(), eq(fixture.company.getId()), eq("VERIFACTU"));
        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.NO_VERIFACTU);
    }

    @Test
    void programaSalidaRealConFechaFinYAckSinCambiarModo() {
        var fixture = realFixture();
        var endDate = LocalDate.now().plusDays(30);
        fixture.configuration.changeMode(FiscalMode.VERIFACTU, Instant.now(), null);
        when(fixture.integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.now(), FiscalMode.VERIFACTU, true, List.of(), 0, 0));

        fixture.service.transition(FiscalMode.NO_VERIFACTU, 1, "cambio legal", true,
                endDate, "ACK-TEST-001");

        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        var captor = ArgumentCaptor.forClass(FiscalModeTransition.class);
        verify(fixture.transitions).save(captor.capture());
        var transition = captor.getValue();
        assertThat(transition.getStatus()).isEqualTo(FiscalModeTransitionStatus.PROGRAMADA);
        assertThat(transition.getVerifactuEndDate()).isEqualTo(endDate);
        assertThat(transition.getAeatAckReference()).isEqualTo("ACK-TEST-001");
        assertThat(transition.getEffectiveAt()).isAfter(transition.getRequestedAt());
    }

    @Test
    void bloqueaSalidaSiLaLicenciaObligaVerifactuEnLaFechaEfectiva() {
        var fixture = realFixture();
        var endDate = LocalDate.now().plusDays(30);
        fixture.configuration.changeMode(FiscalMode.VERIFACTU, Instant.now(), null);
        when(fixture.license.getVerifactuActivationDate()).thenReturn(endDate.plusDays(1));
        when(fixture.integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.now(), FiscalMode.VERIFACTU, true, List.of(), 0, 0));

        assertThatThrownBy(() -> fixture.service.transition(
                FiscalMode.NO_VERIFACTU, 1, "cambio incompatible", true,
                endDate, "ACK-TEST-003"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("licencia obliga VERI*FACTU");
    }

    @Test
    void transicionRealActivaVerifactuAntesDeLaFechaObligatoria() {
        var fixture = realFixture();

        fixture.service.transition(FiscalMode.VERIFACTU, 0, "activacion voluntaria", true);

        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        assertThat(fixture.configuration.isVoluntarilyActive()).isTrue();
        assertThat(new VerifactuActivationService().isActive(
                fixture.configuration, TaxpayerType.SOCIEDAD,
                fixture.license.getVerifactuActivationDate(), Instant.now(),
                java.time.ZoneId.of("Europe/Madrid"))).isTrue();
    }

    @Test
    void permiteActivacionVoluntariaSinFechaAutomaticaEnLaLicencia() {
        var fixture = realFixture();
        when(fixture.license.getVerifactuActivationDate()).thenReturn(null);

        fixture.service.transition(FiscalMode.VERIFACTU, 0,
                "activacion voluntaria sin politica automatica", true);

        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        assertThat(fixture.configuration.isVoluntarilyActive()).isTrue();
    }

    @Test
    void permiteProgramarSalidaSiLaLicenciaNoTieneFechaAutomatica() {
        var fixture = realFixture();
        var endDate = LocalDate.now().plusDays(30);
        fixture.configuration.changeMode(FiscalMode.VERIFACTU, Instant.now(), null);
        when(fixture.license.getVerifactuActivationDate()).thenReturn(null);
        when(fixture.integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.now(), FiscalMode.VERIFACTU, true, List.of(), 0, 0));

        fixture.service.transition(FiscalMode.NO_VERIFACTU, 1,
                "salida voluntaria sin politica automatica", true,
                endDate, "ACK-SIN-POLITICA");

        var captor = ArgumentCaptor.forClass(FiscalModeTransition.class);
        verify(fixture.transitions).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(FiscalModeTransitionStatus.PROGRAMADA);
    }

    @Test
    void bloqueaPreSifANoVerifactuCuandoLaPoliticaDeLicenciaYaEsObligatoria() {
        var fixture = realFixture();
        when(fixture.license.getVerifactuActivationDate()).thenReturn(LocalDate.now().minusDays(1));
        when(fixture.integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.now(), FiscalMode.PRE_SIF, true, List.of(), 0, 0));

        assertThatThrownBy(() -> fixture.service.transition(
                FiscalMode.NO_VERIFACTU, 0, "inicio incompatible", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("licencia obliga VERI*FACTU");
        verify(fixture.integrity, org.mockito.Mockito.never()).check();
        verify(fixture.transitions, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void exigeFechaFinYAckParaSalidaReal() {
        var fixture = realFixture();
        fixture.configuration.changeMode(FiscalMode.VERIFACTU, Instant.now(), null);

        assertThatThrownBy(() -> fixture.service.transition(
                FiscalMode.NO_VERIFACTU, 1, "cambio legal", true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FechaFinVeriFactu");
    }

    @Test
    void noPermiteAcortarLaPermanenciaAnualReal() {
        var fixture = realFixture();
        fixture.configuration.changeMode(FiscalMode.VERIFACTU, Instant.now(), null);
        var blockedUntil = LocalDate.now().plusDays(90);
        fixture.configuration.lockVerifactuUntil(blockedUntil);

        assertThatThrownBy(() -> fixture.service.transition(
                FiscalMode.NO_VERIFACTU, 1, "cambio legal", true,
                blockedUntil.minusDays(1), "ACK-TEST-002"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permanencia anual");
    }

    @Test
    void statusKeepsFailedScheduledTransitionAndLastErrorVisible() {
        var fixture = fixture();
        fixture.configuration.changeMode(
                FiscalMode.VERIFACTU, Instant.parse("2027-01-01T00:00:00Z"), null);
        var scheduled = new FiscalModeTransition(
                fixture.company.getId(), fixture.installationId,
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU,
                Instant.parse("2027-01-02T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), "ADMIN", "fin", 1,
                LocalDate.of(2027, 1, 31), "ACK-FAIL");
        var failure = FiscalModeTransition.failed(
                scheduled, Instant.parse("2027-02-02T00:00:00Z"),
                "TRANSITION_APPLICATION_FAILED", "licencia incompatible");
        when(fixture.transitions
                .findTopByCompanyIdAndInstallationIdAndStatusInOrderByRequestedAtDesc(
                        eq(fixture.company.getId()), eq(fixture.installationId), any()))
                .thenReturn(Optional.of(failure));
        when(fixture.transitions.findById(scheduled.getId()))
                .thenReturn(Optional.of(scheduled));

        var status = fixture.service.status();

        assertThat(status.scheduledTransition()).isNotNull();
        assertThat(status.scheduledTransition().status())
                .isEqualTo(FiscalModeTransitionStatus.FALLIDA);
        assertThat(status.scheduledTransition().effectiveAt())
                .isEqualTo(scheduled.getEffectiveAt());
        assertThat(status.scheduledTransition().lastErrorCode())
                .isEqualTo("TRANSITION_APPLICATION_FAILED");
    }

    private static Fixture fixture() {
        var company = new Company("B00000000", "Company", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var installation = mock(Installation.class);
        var installationId = java.util.UUID.randomUUID();
        when(installation.getId()).thenReturn(installationId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var configuration = new VerifactuConfiguration(company.getId());
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findForUpdateByCompanyId(company.getId()))
                .thenReturn(Optional.of(configuration));
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var transitions = mock(FiscalModeTransitionRepository.class);
        var events = mock(FiscalEventService.class);
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"));
        var integrity = mock(FiscalIntegrityService.class);
        var service = new FiscalModeTransitionService(
                organization, installations, configurations, transitions, runtime, events);
        service.setIntegrityService(integrity);
        return new Fixture(company, installation, installationId, integrity, events, service,
                configuration, organization, installations, configurations, transitions, runtime,
                null, null);
    }

    private static Fixture realFixture() {
        var fixture = fixture();
        var store = mock(Store.class);
        when(store.getId()).thenReturn(java.util.UUID.randomUUID());
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(fixture.organization.currentStore()).thenReturn(store);
        var licenses = mock(LicenseRepository.class);
        var license = mock(License.class);
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(LocalDate.now().plusYears(2));
        when(license.isOperationalAt(any())).thenReturn(true);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                store.getId(), fixture.installationId)).thenReturn(Optional.of(license));
                var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                  .withProperty("tpv.verifactu.runtime-class", "REAL")
                  .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                  .withProperty("tpv.verifactu.transport-mode", "AEAT")
                  .withProperty("tpv.verifactu.aeat-test-network-enabled", "true"));
        var service = new FiscalModeTransitionService(
                fixture.organization, fixture.installations, fixture.configurations,
                fixture.transitions, runtime, fixture.events,
                licenses, new VerifactuActivationService());
        service.setIntegrityService(fixture.integrity);
        return new Fixture(fixture.company, fixture.installation, fixture.installationId,
                fixture.integrity, fixture.events, service, fixture.configuration,
                fixture.organization, fixture.installations, fixture.configurations,
                fixture.transitions, runtime, licenses, license);
    }

    private static Fixture fixtureWithoutConfiguration(FiscalMode initialMode) {
        var fixture = fixture();
        var configuration = new VerifactuConfiguration(fixture.company.getId(), initialMode);
        when(fixture.configurations.findByCompanyId(fixture.company.getId()))
                .thenReturn(Optional.empty());
        when(fixture.configurations.findForUpdateByCompanyId(fixture.company.getId()))
                .thenReturn(Optional.of(configuration));
        return new Fixture(fixture.company, fixture.installation, fixture.installationId,
                fixture.integrity, fixture.events, fixture.service, configuration,
                fixture.organization, fixture.installations, fixture.configurations,
                fixture.transitions, fixture.runtime, fixture.licenses, fixture.license);
    }

    private record Fixture(
            Company company,
            Installation installation,
            java.util.UUID installationId,
            FiscalIntegrityService integrity,
            FiscalEventService events,
            FiscalModeTransitionService service,
            VerifactuConfiguration configuration,
            CurrentOrganization organization,
            InstallationRepository installations,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalRuntimeProperties runtime,
            LicenseRepository licenses,
            License license) {
    }
}
