package com.tpverp.backend.terminal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerminalInterfaceConfigurationRepository
        extends JpaRepository<TerminalInterfaceConfiguration, UUID> {

    Optional<TerminalInterfaceConfiguration> findByTerminalId(UUID terminalId);
}
