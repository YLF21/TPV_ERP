package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
        return new Fixture(company, installation, installationId, integrity, events, service);
    }

    private record Fixture(
            Company company,
            Installation installation,
            java.util.UUID installationId,
            FiscalIntegrityService integrity,
            FiscalEventService events,
            FiscalModeTransitionService service) {
    }
}
