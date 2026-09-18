package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class VerifactuPosServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID TERMINAL_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    @Mock private CurrentOrganization organization;
    @Mock private CurrentTerminal currentTerminal;
    @Mock private TerminalRepository terminals;
    @Mock private FiscalSubmissionStateRepository states;
    @Mock private VerifactuConfigurationRepository configurations;
    @Mock private LicenseRepository licenses;
    @Mock private VerifactuActivationService activation;
    @Mock private Store store;
    @Mock private Company company;
    @Mock private Terminal terminal;

    private Authentication authentication;
    private VerifactuPosService service;

    @BeforeEach
    void setUp() {
        authentication = new UsernamePasswordAuthenticationToken("seller", "session-token");
        service = new VerifactuPosService(
                organization, currentTerminal, terminals, states, configurations,
                licenses, activation, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void queueUsesAuthenticatedTerminalAndCurrentOrganizationAndCapsLimit() {
        authenticatedScope();
        var record = new VerifactuPosQueueRecord(
                "T-2026-0001", FiscalDocumentType.F2,
                FiscalSubmissionStatus.RECHAZADO, NOW);
        when(states.findPosQueue(
                COMPANY_ID, STORE_ID, TERMINAL_ID, posQueueStatuses(),
                PageRequest.of(0, 50)))
                .thenReturn(List.of(record));

        var result = service.queue(500, authentication);

        assertThat(result).containsExactly(new VerifactuPosQueueItem(
                "T-2026-0001", FiscalDocumentType.F2,
                FiscalSubmissionStatus.RECHAZADO, NOW,
                "VERIFACTU_REVIEW_REQUIRED"));
        verify(currentTerminal).terminalId(authentication);
        verify(terminals).findByIdAndTiendaId(TERMINAL_ID, STORE_ID);
        verify(states).findPosQueue(
                COMPANY_ID, STORE_ID, TERMINAL_ID, posQueueStatuses(),
                PageRequest.of(0, 50));
    }

    @Test
    void queueHonoursARequestedLimitBelowTheBackendMaximum() {
        authenticatedScope();
        when(states.findPosQueue(
                COMPANY_ID, STORE_ID, TERMINAL_ID, posQueueStatuses(),
                PageRequest.of(0, 12)))
                .thenReturn(List.of());

        assertThat(service.queue(12, authentication)).isEmpty();

        verify(states).findPosQueue(
                COMPANY_ID, STORE_ID, TERMINAL_ID, posQueueStatuses(),
                PageRequest.of(0, 12));
    }

    @Test
    void invalidQueueLimitIsRejectedBeforeResolvingAnyScope() {
        assertThatThrownBy(() -> service.queue(0, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        verify(currentTerminal, never()).terminalId(authentication);
        verify(states, never()).findPosQueue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void terminalFromAnotherStoreIsRejectedBeforeReadingFiscalData() {
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(STORE_ID);
        when(currentTerminal.terminalId(authentication)).thenReturn(TERMINAL_ID);
        when(terminals.findByIdAndTiendaId(TERMINAL_ID, STORE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.queue(50, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tienda activa");

        verify(states, never()).findPosQueue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void statusCountsOnlyTheAuthenticatedTerminalAndPrioritizesReview() {
        authenticatedScope();
        effectiveVerifactuMode();
        when(states.countPosQueueByStatusIn(
                COMPANY_ID, STORE_ID, TERMINAL_ID,
                List.of(FiscalSubmissionStatus.PENDIENTE, FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(4L);
        when(states.countPosQueueByStatusIn(
                COMPANY_ID, STORE_ID, TERMINAL_ID,
                List.of(FiscalSubmissionStatus.ENVIANDO)))
                .thenReturn(1L);
        when(states.countPosQueueByStatusIn(
                COMPANY_ID, STORE_ID, TERMINAL_ID,
                List.of(FiscalSubmissionStatus.RECHAZADO,
                        FiscalSubmissionStatus.DEFECTUOSO,
                        FiscalSubmissionStatus.ACEPTADO_CON_ERRORES)))
                .thenReturn(2L);

        var result = service.status(authentication);

        assertThat(result).isEqualTo(new VerifactuPosStatusView(
                true, VerifactuPosPresentationStatus.REQUIERE_REVISION,
                4, 1, 2));
    }

    @Test
    void inactiveVerifactuAlwaysPresentsInactiveEvenWithOldQueueEntries() {
        authenticatedScope();
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        when(states.countPosQueueByStatusIn(
                org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TERMINAL_ID),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(3L);

        var result = service.status(authentication);

        assertThat(result.active()).isFalse();
        assertThat(result.presentationStatus())
                .isEqualTo(VerifactuPosPresentationStatus.INACTIVO);
    }

    @ParameterizedTest
    @CsvSource({"PRE_SIF,false", "PRE_SIF,true", "NO_VERIFACTU,false",
            "NO_VERIFACTU,true", "VERIFACTU,false", "VERIFACTU,true"})
    void effectiveModeWinsOverLegacyActivationAndLicencePolicy(
            FiscalMode mode, boolean legacyVoluntaryActivation) {
        authenticatedScope();
        var configuration = new VerifactuConfiguration(COMPANY_ID, mode);
        if (legacyVoluntaryActivation) {
            configuration.activateVoluntarily(NOW.minusSeconds(60));
        }
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(configuration));

        var result = service.status(authentication);

        assertThat(result.fiscalMode()).isEqualTo(mode);
        assertThat(result.active()).isEqualTo(mode == FiscalMode.VERIFACTU);
        assertThat(result.presentationStatus()).isEqualTo(mode == FiscalMode.VERIFACTU
                ? VerifactuPosPresentationStatus.OPERATIVO
                : VerifactuPosPresentationStatus.INACTIVO);
        // Policy expiry does not pretend that a pending transition was applied.
        verifyNoInteractions(licenses, activation);
    }

    @ParameterizedTest
    @EnumSource(FiscalMode.class)
    void sandboxWithoutConfigurationUsesTheSameInitialModeAsFiscalStatus(FiscalMode mode) {
        authenticatedScope();
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        var runtime = mock(FiscalRuntimeProperties.class);
        when(runtime.isSandbox()).thenReturn(true);
        when(runtime.sandboxInitialMode()).thenReturn(mode);
        when(runtime.runtimeClass()).thenReturn(FiscalRuntimeClass.SANDBOX);
        when(runtime.endpointEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        when(runtime.transportMode()).thenReturn(FiscalTransportMode.SIMULATED);
        service.setRuntime(runtime);

        var result = service.status(authentication);

        assertThat(result.fiscalMode()).isEqualTo(mode);
        assertThat(result.active()).isEqualTo(mode == FiscalMode.VERIFACTU);
        assertThat(result.runtimeClass()).isEqualTo(FiscalRuntimeClass.SANDBOX);
        verifyNoInteractions(licenses, activation);
    }

    private void authenticatedScope() {
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(STORE_ID);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(currentTerminal.terminalId(authentication)).thenReturn(TERMINAL_ID);
        when(terminals.findByIdAndTiendaId(TERMINAL_ID, STORE_ID))
                .thenReturn(Optional.of(terminal));
        when(terminal.isActiva()).thenReturn(true);
        when(terminal.isAprobada()).thenReturn(true);
    }

    private void effectiveVerifactuMode() {
        var configuration = mock(VerifactuConfiguration.class);
        when(configuration.getCurrentMode()).thenReturn(FiscalMode.VERIFACTU);
        when(configurations.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(configuration));
    }

    private static List<FiscalSubmissionStatus> posQueueStatuses() {
        return List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO,
                FiscalSubmissionStatus.RECHAZADO,
                FiscalSubmissionStatus.DEFECTUOSO,
                FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);
    }
}
