package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class FiscalMandatoryActivationServiceTest {

    private static final Instant DUE_AT = Instant.parse("2027-01-01T00:00:00Z");

    @Test
    void noActivaAntesDelInicioLocalAsignadoPorLaLicencia() {
        var fixture = fixture(FiscalMode.PRE_SIF);

        assertThat(fixture.service.activateIfDue(
                fixture.licenseId, DUE_AT.minusNanos(1))).isFalse();

        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.PRE_SIF);
        verify(fixture.transitions, never()).save(any());
    }

    @Test
    void revalidaLaHoraTrasElBloqueoSiLaEsperaCruzaLaFechaObligatoria() {
        var fixture = fixture(FiscalMode.NO_VERIFACTU, Clock.fixed(DUE_AT, ZoneOffset.UTC));

        var prepared = fixture.service.prepareEmission(
                fixture.licenseId, DUE_AT.minusNanos(1));

        assertThat(prepared.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        verify(fixture.transitions).save(any(FiscalModeTransition.class));
    }

    @Test
    void bloqueaSinEmitirNoSiElBloqueoCompartidoCruzaLaFrontera() {
        var boundaryClock = mock(Clock.class);
        when(boundaryClock.instant()).thenReturn(
                DUE_AT.minusNanos(1), DUE_AT);
        var fixture = fixture(FiscalMode.NO_VERIFACTU, boundaryClock);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                fixture.service.prepareEmission(
                        fixture.licenseId, DUE_AT.minusNanos(1)))
                .isInstanceOf(FiscalMandatoryActivationException.class)
                .hasMessageContaining("cruzo la fecha automatica de licencia");

        assertThat(fixture.configuration.getCurrentMode())
                .isEqualTo(FiscalMode.NO_VERIFACTU);
        verify(fixture.transitions, never()).save(any());
    }

    @Test
    void antesDeLaFechaLaEmisionUsaBloqueoCompartidoSinSerializarTodasLasCajas() {
        var fixture = fixture(FiscalMode.NO_VERIFACTU);

        var prepared = fixture.service.prepareEmission(
                fixture.licenseId, DUE_AT.minusSeconds(1));

        assertThat(prepared.getCurrentMode()).isEqualTo(FiscalMode.NO_VERIFACTU);
        verify(fixture.configurations).findForEmissionByCompanyId(fixture.companyId);
        verify(fixture.configurations, never()).findForUpdateByCompanyId(fixture.companyId);
    }

    @Test
    void unaLicenciaSinPoliticaExplicitaConservaElModoNoVerifactu() {
        var fixture = fixture(FiscalMode.NO_VERIFACTU);
        when(fixture.license.getVerifactuActivationDate()).thenReturn(null);

        var prepared = fixture.service.prepareEmission(fixture.licenseId, DUE_AT);

        assertThat(prepared.getCurrentMode()).isEqualTo(FiscalMode.NO_VERIFACTU);
        verify(fixture.transitions, never()).save(any());
    }

    @Test
    void activaEnLaFechaExactaYConservaLaFechaEfectivaEnLaAuditoria() {
        var fixture = fixture(FiscalMode.PRE_SIF);
        Instant workerAt = DUE_AT.plusSeconds(37);

        assertThat(fixture.service.activateIfDue(fixture.licenseId, workerAt)).isTrue();

        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        assertThat(fixture.configuration.getModeSince()).isEqualTo(DUE_AT);
        assertThat(fixture.configuration.getModeVersion()).isEqualTo(1);
        var transition = ArgumentCaptor.forClass(FiscalModeTransition.class);
        verify(fixture.transitions).save(transition.capture());
        assertThat(transition.getValue().getPreviousMode()).isEqualTo(FiscalMode.PRE_SIF);
        assertThat(transition.getValue().getNewMode()).isEqualTo(FiscalMode.VERIFACTU);
        assertThat(transition.getValue().getRequestedAt()).isEqualTo(workerAt);
        assertThat(transition.getValue().getEffectiveAt()).isEqualTo(DUE_AT);
        assertThat(transition.getValue().getCause())
                .isEqualTo(FiscalMandatoryActivationService.CAUSE);
        assertThat(transition.getValue().getExpectedVersion()).isZero();
    }

    @Test
    void cierraNoVerifactuAntesDeAplicarLaModalidadObligatoria() {
        var fixture = fixture(FiscalMode.NO_VERIFACTU);

        assertThat(fixture.service.activateIfDue(fixture.licenseId, DUE_AT)).isTrue();

        verify(fixture.events).create(
                eq(fixture.companyId), eq(fixture.installationId),
                eq(FiscalMode.NO_VERIFACTU), eq(FiscalEventType.END_NO_VERIFACTU),
                org.mockito.ArgumentMatchers.contains("LIC-DEV-01"));
        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
    }

    @Test
    void esIdempotenteCuandoVerifactuYaEstaActivo() {
        var fixture = fixture(FiscalMode.VERIFACTU);

        assertThat(fixture.service.activateIfDue(
                fixture.licenseId, DUE_AT.plusSeconds(60))).isFalse();

        verify(fixture.transitions, never()).save(any());
        verify(fixture.events, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void preparaLaEmisionBajoBloqueoYAplicaLaTransicionEnLaFronteraExacta() {
        var fixture = fixture(FiscalMode.NO_VERIFACTU);

        var prepared = fixture.service.prepareEmission(fixture.licenseId, DUE_AT);

        assertThat(prepared).isSameAs(fixture.configuration);
        assertThat(prepared.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        verify(fixture.configurations).findForUpdateByCompanyId(fixture.companyId);
    }

    @Test
    void dosComprobacionesConcurrentementeSerializadasSoloCreanUnaTransicion() {
        var fixture = fixture(FiscalMode.NO_VERIFACTU);

        assertThat(fixture.service.activateIfDue(fixture.licenseId, DUE_AT)).isTrue();
        assertThat(fixture.service.activateIfDue(
                fixture.licenseId, DUE_AT.plusMillis(1))).isFalse();

        assertThat(fixture.configuration.getCurrentMode()).isEqualTo(FiscalMode.VERIFACTU);
        verify(fixture.transitions, times(1)).save(any(FiscalModeTransition.class));
        verify(fixture.events, times(1)).create(
                eq(fixture.companyId), eq(fixture.installationId),
                eq(FiscalMode.NO_VERIFACTU), eq(FiscalEventType.END_NO_VERIFACTU), any());
    }

    @Test
    void laPrecondicionSeApoyaEnBloqueoPesimistaDeLaConfiguracionFiscal()
            throws NoSuchMethodException {
        var lock = VerifactuConfigurationRepository.class
                .getDeclaredMethod("findForUpdateByCompanyId", UUID.class)
                .getAnnotation(org.springframework.data.jpa.repository.Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var readLock = VerifactuConfigurationRepository.class
                .getDeclaredMethod("findForEmissionByCompanyId", UUID.class)
                .getAnnotation(org.springframework.data.jpa.repository.Lock.class);
        assertThat(readLock).isNotNull();
        assertThat(readLock.value()).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        var transaction = FiscalMandatoryActivationService.class
                .getDeclaredMethod("prepareEmission", UUID.class, Instant.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
    }

    @Test
    void bloqueaLaEmisionConErrorFiscalClaroSiNoPuedeAdquirirLaConfiguracion() {
        var fixture = fixture(FiscalMode.NO_VERIFACTU);
        when(fixture.configurations.findForUpdateByCompanyId(fixture.companyId))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                fixture.service.prepareEmission(fixture.licenseId, DUE_AT))
                .isInstanceOf(FiscalMandatoryActivationException.class)
                .hasMessageContaining("Emision fiscal bloqueada")
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(fixture.transitions, never()).save(any());
    }

    private static Fixture fixture(FiscalMode mode) {
        return fixture(mode, Clock.fixed(DUE_AT.minusSeconds(3600), ZoneOffset.UTC));
    }

    private static Fixture fixture(FiscalMode mode, Clock clock) {
        var company = new Company("B00000000", "Company", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
        var store = new Store(company, "001", "Tienda", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"),
                "hash", "Atlantic/Canary", "EUR", "es-ES");
        UUID licenseId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        var license = mock(License.class);
        when(license.getId()).thenReturn(licenseId);
        when(license.isActiva()).thenReturn(true);
        when(license.getTiendaId()).thenReturn(store.getId());
        when(license.getLocalCompanyId()).thenReturn(company.getId());
        when(license.getInstalacionId()).thenReturn(installationId);
        when(license.getReferencia()).thenReturn("LIC-DEV-01");
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(LocalDate.of(2027, 1, 1));
        when(license.getVerifactuPolicyVersion()).thenReturn(4L);
        var licenses = mock(LicenseRepository.class);
        when(licenses.findById(licenseId)).thenReturn(Optional.of(license));
        var stores = mock(StoreRepository.class);
        when(stores.findWithCompanyById(store.getId())).thenReturn(Optional.of(store));
        var configuration = new VerifactuConfiguration(company.getId());
        if (mode != FiscalMode.PRE_SIF) {
            configuration.changeMode(mode, Instant.parse("2026-01-01T00:00:00Z"), null);
        }
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findByCompanyId(company.getId()))
                .thenReturn(Optional.of(configuration));
        when(configurations.findForUpdateByCompanyId(company.getId()))
                .thenReturn(Optional.of(configuration));
        when(configurations.findForEmissionByCompanyId(company.getId()))
                .thenReturn(Optional.of(configuration));
        var transitions = mock(FiscalModeTransitionRepository.class);
        var events = mock(FiscalEventService.class);
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT")
                .withProperty("tpv.verifactu.aeat-test-network-enabled", "true"));
        var service = new FiscalMandatoryActivationService(
                licenses, stores, configurations, transitions, events,
                new VerifactuActivationService(), runtime, clock);
        return new Fixture(service, configuration, configurations, transitions, events,
                license, licenseId, company.getId(), installationId);
    }

    private record Fixture(
            FiscalMandatoryActivationService service,
            VerifactuConfiguration configuration,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalEventService events,
            License license,
            UUID licenseId,
            UUID companyId,
            UUID installationId) {
    }
}
