package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.tpverp.backend.terminal.secrets.PaymentSecretStore;

@ExtendWith(MockitoExtension.class)
class TerminalPaymentConfigurationServiceTest {

    @Mock
    private TerminalPaymentConfigurationRepository configurations;
    @Mock
    private StorePaymentConfigurationRepository storeConfigurations;
    @Mock
    private TerminalRepository terminals;
    @Mock
    private CurrentTerminal currentTerminal;
    @Mock
    private CardTerminalGateway gateway;
    @Mock
    private CardTerminalConfigurationReader gatewayConfigurations;
    @Mock private PaymentSecretStore secretStore;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-04T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void returnsDefaultManualConfigurationForCurrentTerminal() {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());
        when(storeConfigurations.findByStoreId(terminal.getTienda().getId())).thenReturn(Optional.empty());

        var view = service().current();

        assertThat(view.terminalId()).isEqualTo(terminal.getId());
        assertThat(view.rules().cardManualEnabled()).isTrue();
        assertThat(view.configuration().cardMode()).isEqualTo(PaymentCardMode.MANUAL);
        assertThat(view.configuration().provider()).isEqualTo(PaymentTerminalProvider.NONE);
    }

    @Test
    void publishesLiveAvailabilityReportedByTheLocalBridgeGateway() {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());
        when(storeConfigurations.findByStoreId(terminal.getTienda().getId())).thenReturn(Optional.empty());
        when(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC, false)).thenReturn(true);
        when(gateway.capabilities()).thenReturn(java.util.Set.of(
                PaymentTerminalCapability.CONNECTION_TEST, PaymentTerminalCapability.CHARGE,
                PaymentTerminalCapability.QUERY));

        var view = service().current();

        assertThat(view.providerDescriptors()).filteredOn(descriptor -> descriptor.provider() == PaymentTerminalProvider.REDSYS_TPV_PC)
                .singleElement().satisfies(descriptor -> {
                    assertThat(descriptor.liveAvailable()).isTrue();
                    assertThat(descriptor.unavailableReason()).isNull();
                });
        assertThat(view.providerDescriptors()).filteredOn(descriptor -> descriptor.provider() == PaymentTerminalProvider.GLOBAL_PAYMENTS)
                .singleElement().satisfies(descriptor -> assertThat(descriptor.liveAvailable()).isFalse());
    }

    @Test
    void savesConfigurationForCurrentTerminalOnly() {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());
        when(configurations.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC,
                "PinPad caja 1",
                true,
                true,
                Map.of("ip", "192.168.1.50"),
                null);

        var view = service().update(request);

        var saved = ArgumentCaptor.forClass(TerminalPaymentConfiguration.class);
        org.mockito.Mockito.verify(configurations).save(saved.capture());
        assertThat(saved.getValue().getTerminal().getId()).isEqualTo(terminal.getId());
        assertThat(view.configuration().secretConfigured()).isFalse();
    }

    @Test
    void executesConnectionTestWithConfiguredGatewayAndRecordsStatus() {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys",
                true, true, Map.of("simulatorOutcome", "APPROVED"), null));
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        var detached=CardTerminalConfiguration.from(configuration);
        when(gatewayConfigurations.required(terminal.getId())).thenReturn(detached);
        when(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC, true)).thenReturn(true);
        when(gateway.testConnection(detached)).thenReturn(new CardTerminalResult(
                PaymentTerminalOperationStatus.APPROVED, "SIM-CONNECTION", "SIMAUTH", "OK"));
        configuration.recordConnectionTest(true,clock.instant());
        when(gatewayConfigurations.recordAndView(terminal.getId(),true)).thenReturn(TerminalPaymentConfigurationView.from(terminal,new StorePaymentConfiguration(terminal.getTienda()),configuration));

        var view = service().testConnection();

        assertThat(view.configuration().lastConnectionStatus()).isEqualTo("OK");
        assertThat(view.configuration().lastConnectionTestAt()).isEqualTo(Instant.parse("2026-07-04T10:15:30Z"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void preservesExistingSecretReferenceWhenPatchDoesNotSupplyANewOne(String omittedSecret) {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys",
                true, true, Map.of("simulatorOutcome", "APPROVED"), "pts_0123456789abcdef0123456789abcdef",1));
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.of(configuration));
        when(secretStore.describe("pts_0123456789abcdef0123456789abcdef")).thenReturn(new PaymentSecretStore.SecretMetadata("pts_0123456789abcdef0123456789abcdef","REDSYS_TPV_PC",1));

        var view = service().update(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys updated",
                true, true, Map.of("simulatorOutcome", "DECLINED"), omittedSecret));

        assertThat(configuration.getSecretReference()).isEqualTo("pts_0123456789abcdef0123456789abcdef");
        assertThat(view.configuration().secretConfigured()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "declined", "Timeout", "connection_error"})
    void acceptsAndNormalizesSupportedSimulatorOutcomes(String outcome) {
        var configuration = updateRedsysSimulator(outcome);

        assertThat(configuration.getProviderParameters().get("simulatorOutcome"))
                .isEqualTo(outcome.toUpperCase(java.util.Locale.ROOT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DECLINED", "TIMEOUT", "CONNECTION_ERROR"})
    void recordsNonApprovedConnectionResultAsError(String outcome) {
        var terminal = terminal();
        var configuration = configuredRedsys(terminal, outcome);
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        var detached=CardTerminalConfiguration.from(configuration);
        when(gatewayConfigurations.required(terminal.getId())).thenReturn(detached);
        when(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC, true)).thenReturn(true);
        when(gateway.testConnection(detached)).thenReturn(new CardTerminalResult(
                PaymentTerminalOperationStatus.valueOf(
                        outcome.equals("CONNECTION_ERROR") ? "ERROR" : outcome),
                "SIM-CONNECTION", null, "Fallo simulado"));
        configuration.recordConnectionTest(false,clock.instant());
        when(gatewayConfigurations.recordAndView(terminal.getId(),false)).thenReturn(TerminalPaymentConfigurationView.from(terminal,new StorePaymentConfiguration(terminal.getTienda()),configuration));

        var view = service().testConnection();

        assertThat(view.configuration().lastConnectionStatus()).isEqualTo("ERROR");
        assertThat(view.configuration().lastConnectionTestAt()).isEqualTo(clock.instant());
    }

    @Test
    void reportsExplicitErrorWhenNoGatewaySupportsConfigurationWithoutRecordingSuccess() {
        var terminal = terminal();
        var configuration = configuredRedsys(terminal, "APPROVED");
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        var detached=CardTerminalConfiguration.from(configuration);
        when(gatewayConfigurations.required(terminal.getId())).thenReturn(detached);
        when(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC, true)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().testConnection())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.payment_terminal.gateway_not_available");
        assertThat(configuration.getLastConnectionStatus()).isNull();
        assertThat(configuration.getLastConnectionTestAt()).isNull();
    }

    @Test
    void acceptsAndPublishesTheTimeoutQueryOutcome() {
        var terminal=terminal();
        var configuration=TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.PAYTEF,"Paytef",true,true,
                Map.of("simulatorOutcome","TIMEOUT","simulatorQueryOutcome","approved"),null));
        var view=TerminalPaymentConfigurationView.from(terminal,new StorePaymentConfiguration(terminal.getTienda()),configuration);
        assertThat(view.configuration().providerParameters()).containsEntry("simulatorQueryOutcome","APPROVED");
    }

    @Test
    void startsAndQueriesPairingThroughTheConfiguredSimulatorGateway() {
        var terminal = terminal();
        var configuration = configuredRedsys(terminal, "APPROVED");
        var pairingId = java.util.UUID.randomUUID();
        var detached = CardTerminalConfiguration.from(configuration);
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.of(configuration));
        when(configurations.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->invocation.getArgument(0));
        when(gatewayConfigurations.required(terminal.getId())).thenReturn(detached);
        when(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC, true)).thenReturn(true);
        when(gateway.capabilities()).thenReturn(java.util.Set.of(PaymentTerminalCapability.PAIRING));
        when(gateway.pair(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED, "PAIRED", "ref", null, "ok"));
        when(gateway.pairingStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED, "PAIRED", "ref", null, "ok"));

        assertThat(service().pair(pairingId).code()).isEqualTo("PAIRED");
        assertThat(service().pairingStatus(pairingId).code()).isEqualTo("PAIRED");
        assertThat(service().current().configuration().pairingStatus()).isEqualTo("PAIRED");
        org.mockito.Mockito.verify(configurations,org.mockito.Mockito.atLeastOnce()).save(configuration);
        var context = ArgumentCaptor.forClass(PaymentTerminalGatewayContext.class);
        org.mockito.Mockito.verify(gateway).pair(
                org.mockito.ArgumentMatchers.eq(new PaymentTerminalPairCommand(pairingId)), context.capture());
        assertThat(context.getValue().mode()).isEqualTo(PaymentTerminalMode.SIMULATED);
    }

    @Test
    void patchingTheSameProviderPreservesPairingIdentityAndStatus() {
        var terminal = terminal();
        var configuration = configuredRedsys(terminal, "APPROVED");
        var pairingId = java.util.UUID.randomUUID();
        configuration.recordPairing(pairingId, new PaymentTerminalResult(
                PaymentTerminalOperationStatus.APPROVED, "PAIRED", "pair-ref", null, "ok"));
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.of(configuration));

        var view = service().update(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys updated",
                true, true, Map.of("simulatorOutcome", "DECLINED"), null));

        assertThat(view.configuration().pairingId()).isEqualTo(pairingId);
        assertThat(view.configuration().pairingStatus()).isEqualTo("PAIRED");
        assertThat(view.configuration().providerParameters()).doesNotContainKeys("_pairingId", "_pairingStatus");
    }

    @Test
    void rejectsPairingStatusForAnIdentityThatWasNeverStarted() {
        var terminal=terminal();var configuration=configuredRedsys(terminal,"APPROVED");
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.of(configuration));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service().pairingStatus(java.util.UUID.randomUUID()))
                .hasMessage("message.payment_terminal.pairing_not_started");
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.never()).pairingStatus(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void livePairingReturnsTypedSdkNotInstalledResult() {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.PAYTEF, "Paytef", true, false, Map.of(), null));
        var unavailable = new UnavailableLivePaymentTerminalGateway(PaymentTerminalProvider.PAYTEF);
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(gatewayConfigurations.required(terminal.getId())).thenReturn(CardTerminalConfiguration.from(configuration));
        var service = new TerminalPaymentConfigurationService(configurations, storeConfigurations, terminals,
                currentTerminal, List.of(unavailable), gatewayConfigurations, clock, secretStore);

        assertThat(service.pair(java.util.UUID.randomUUID()).code()).isEqualTo("SDK_NOT_INSTALLED");
    }

    @Test
    void rejectsLivePairingWhenGatewayDoesNotAdvertisePairingCapability() {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.PAYTEF, "Paytef", true, false, Map.of(), null));
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(gatewayConfigurations.required(terminal.getId())).thenReturn(CardTerminalConfiguration.from(configuration));
        when(gateway.supports(PaymentTerminalProvider.PAYTEF, false)).thenReturn(true);
        when(gateway.capabilities()).thenReturn(java.util.Set.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().pair(java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.payment_terminal.pairing_not_supported");
    }

    @Test
    void rejectsUnknownSimulatorOutcome() {
        assertThatInvalidConfiguration(PaymentTerminalProvider.REDSYS_TPV_PC, true, "SURPRISE");
    }

    @Test
    void rejectsSimulatorOutcomeOutsideTestMode() {
        assertThatInvalidConfiguration(PaymentTerminalProvider.REDSYS_TPV_PC, false, "APPROVED");
        assertThatInvalidConfiguration(PaymentTerminalProvider.PAYTEF, false, "APPROVED");
    }

    @Test
    void rejectsSensitiveProviderParameterNames() {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());
        when(configurations.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.PAYTEF,
                "Paytef",
                true,
                false,
                Map.of("apiKey", "secret"),
                null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().update(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment_terminal.sensitive_parameter_not_allowed");
    }

    private TerminalPaymentConfigurationService service() {
        return new TerminalPaymentConfigurationService(
                configurations, storeConfigurations, terminals, currentTerminal, List.of(gateway), gatewayConfigurations, clock,secretStore);
    }

    private void assertThatInvalidConfiguration(
            PaymentTerminalProvider provider, boolean testMode, String outcome) {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());
        when(configurations.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, provider, "Terminal", true, testMode,
                Map.of("simulatorOutcome", outcome), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().update(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment_terminal.simulator_outcome_invalid");
    }

    private TerminalPaymentConfiguration updateRedsysSimulator(String outcome) {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());
        when(configurations.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        service().update(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys",
                true, true, Map.of("simulatorOutcome", outcome), null));
        var saved = ArgumentCaptor.forClass(TerminalPaymentConfiguration.class);
        org.mockito.Mockito.verify(configurations).save(saved.capture());
        return saved.getValue();
    }

    private TerminalPaymentConfiguration configuredRedsys(Terminal terminal, String outcome) {
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys",
                true, true, Map.of("simulatorOutcome", outcome), null));
        return configuration;
    }

    private Terminal terminal() {
        var company = new Company("B12345678", "Demo SL", Map.of(
                "linea1", "Calle A",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES"));
        var store = new Store(company, "Caja", Map.of(
                "linea1", "Calle A",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES"), "hash", "Atlantic/Canary", "EUR", "es-ES");
        return new Terminal(store, "CAJA 1", TerminalType.TERMINAL_VENTA, "credential");
    }
}
