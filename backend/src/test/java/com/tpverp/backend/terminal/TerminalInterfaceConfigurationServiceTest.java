package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TerminalInterfaceConfigurationServiceTest {

    @Mock
    private TerminalInterfaceConfigurationRepository configurations;
    @Mock
    private TerminalRepository terminals;
    @Mock
    private CurrentTerminal currentTerminal;

    @Test
    void defaultsToKeyboardWithoutCreatingAConfiguration() {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());

        var view = service().current();

        assertThat(view.terminalId()).isEqualTo(terminal.getId());
        assertThat(view.saleMode()).isEqualTo(SaleInterfaceMode.KEYBOARD);
    }

    @Test
    void storesTouchModeForTheAuthenticatedTerminalOnly() {
        var terminal = terminal();
        when(currentTerminal.terminalId(null)).thenReturn(terminal.getId());
        when(terminals.findById(terminal.getId())).thenReturn(Optional.of(terminal));
        when(configurations.findByTerminalId(terminal.getId())).thenReturn(Optional.empty());
        when(configurations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var view = service().update(SaleInterfaceMode.TOUCH);

        var saved = ArgumentCaptor.forClass(TerminalInterfaceConfiguration.class);
        verify(configurations).save(saved.capture());
        assertThat(saved.getValue().getTerminal().getId()).isEqualTo(terminal.getId());
        assertThat(saved.getValue().getSaleMode()).isEqualTo(SaleInterfaceMode.TOUCH);
        assertThat(view.saleMode()).isEqualTo(SaleInterfaceMode.TOUCH);
    }

    private TerminalInterfaceConfigurationService service() {
        return new TerminalInterfaceConfigurationService(configurations, terminals, currentTerminal);
    }

    private static Terminal terminal() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        var company = new Company("B76000001", "Empresa", address);
        var store = new Store(company, "Tienda", address, "address-hash",
                "Atlantic/Canary", "EUR", "es-ES");
        return new Terminal(store, "Caja 01", TerminalType.TERMINAL_VENTA, "credential-hash");
    }
}
