package com.tpverp.backend.terminal;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminalInterfaceConfigurationService {

    private final TerminalInterfaceConfigurationRepository configurations;
    private final TerminalRepository terminals;
    private final CurrentTerminal currentTerminal;

    public TerminalInterfaceConfigurationService(
            TerminalInterfaceConfigurationRepository configurations,
            TerminalRepository terminals,
            CurrentTerminal currentTerminal) {
        this.configurations = configurations;
        this.terminals = terminals;
        this.currentTerminal = currentTerminal;
    }

    @Transactional(readOnly = true)
    public View current() {
        var terminal = currentTerminal();
        var mode = configurations.findByTerminalId(terminal.getId())
                .map(TerminalInterfaceConfiguration::getSaleMode)
                .orElse(SaleInterfaceMode.KEYBOARD);
        return new View(terminal.getId(), mode);
    }

    @Transactional
    public View update(SaleInterfaceMode saleMode) {
        var terminal = currentTerminal();
        var configuration = configurations.findByTerminalId(terminal.getId())
                .orElseGet(() -> TerminalInterfaceConfiguration.keyboard(terminal));
        configuration.select(saleMode);
        configurations.save(configuration);
        return new View(terminal.getId(), configuration.getSaleMode());
    }

    private Terminal currentTerminal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID terminalId = currentTerminal.terminalId(authentication);
        return terminals.findById(terminalId)
                .orElseThrow(() -> new IllegalStateException("message.terminal.not_found"));
    }

    public record View(UUID terminalId, SaleInterfaceMode saleMode) {
    }
}
