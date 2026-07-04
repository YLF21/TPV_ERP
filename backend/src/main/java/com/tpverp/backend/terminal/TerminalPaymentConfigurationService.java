package com.tpverp.backend.terminal;

import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminalPaymentConfigurationService {

    private final TerminalPaymentConfigurationRepository configurations;
    private final StorePaymentConfigurationRepository storeConfigurations;
    private final TerminalRepository terminals;
    private final CurrentTerminal currentTerminal;
    private final Clock clock;

    public TerminalPaymentConfigurationService(
            TerminalPaymentConfigurationRepository configurations,
            StorePaymentConfigurationRepository storeConfigurations,
            TerminalRepository terminals,
            CurrentTerminal currentTerminal,
            Clock clock) {
        this.configurations = configurations;
        this.storeConfigurations = storeConfigurations;
        this.terminals = terminals;
        this.currentTerminal = currentTerminal;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TerminalPaymentConfigurationView current() {
        var terminal = currentTerminal();
        return TerminalPaymentConfigurationView.from(terminal, storeRules(terminal), terminalConfiguration(terminal));
    }

    @Transactional
    public TerminalPaymentConfigurationView update(TerminalPaymentConfigurationCommand command) {
        var terminal = currentTerminal();
        var configuration = configurations.findByTerminalId(terminal.getId())
                .orElseGet(() -> configurations.save(TerminalPaymentConfiguration.manual(terminal)));
        configuration.configure(command);
        return TerminalPaymentConfigurationView.from(terminal, storeRules(terminal), configuration);
    }

    @Transactional
    public TerminalPaymentConfigurationView recordConnectionTest(ConnectionTestResultCommand command) {
        var terminal = currentTerminal();
        var configuration = configurations.findByTerminalId(terminal.getId())
                .orElseGet(() -> configurations.save(TerminalPaymentConfiguration.manual(terminal)));
        configuration.recordConnectionTest(command.success(), clock.instant());
        return TerminalPaymentConfigurationView.from(terminal, storeRules(terminal), configuration);
    }

    private Terminal currentTerminal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var terminalId = currentTerminal.terminalId(authentication);
        return terminals.findById(terminalId)
                .orElseThrow(() -> new IllegalStateException("message.terminal.not_found"));
    }

    private TerminalPaymentConfiguration terminalConfiguration(Terminal terminal) {
        return configurations.findByTerminalId(terminal.getId())
                .orElseGet(() -> TerminalPaymentConfiguration.manual(terminal));
    }

    private StorePaymentConfiguration storeRules(Terminal terminal) {
        return storeConfigurations.findByStoreId(terminal.getTienda().getId())
                .orElseGet(() -> new StorePaymentConfiguration(terminal.getTienda()));
    }
}
