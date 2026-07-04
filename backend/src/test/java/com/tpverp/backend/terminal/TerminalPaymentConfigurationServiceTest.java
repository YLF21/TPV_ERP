package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                "secret:redsys:caja1");

        var view = service().update(request);

        var saved = ArgumentCaptor.forClass(TerminalPaymentConfiguration.class);
        org.mockito.Mockito.verify(configurations).save(saved.capture());
        assertThat(saved.getValue().getTerminal().getId()).isEqualTo(terminal.getId());
        assertThat(view.configuration().secretConfigured()).isTrue();
    }

    @Test
    void recordsConnectionTestStatusWithoutStoringSensitivePayloads() {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.of(configuration));

        var view = service().recordConnectionTest(new ConnectionTestResultCommand(false, "Timeout con proveedor"));

        assertThat(view.configuration().lastConnectionStatus()).isEqualTo("ERROR");
        assertThat(view.configuration().lastConnectionTestAt()).isEqualTo(Instant.parse("2026-07-04T10:15:30Z"));
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
                configurations, storeConfigurations, terminals, currentTerminal, clock);
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
