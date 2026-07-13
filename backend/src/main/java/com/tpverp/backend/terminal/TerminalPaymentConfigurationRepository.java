package com.tpverp.backend.terminal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TerminalPaymentConfigurationRepository extends JpaRepository<TerminalPaymentConfiguration, UUID> {

    Optional<TerminalPaymentConfiguration> findByTerminalId(UUID terminalId);
    boolean existsBySecretReference(String secretReference);
    boolean existsBySecretReferenceAndTerminalId(String secretReference,UUID terminalId);
    @Modifying
    @Query("update TerminalPaymentConfiguration c set c.secretReferenceVersion=:version where c.secretReference=:reference and c.terminal.id=:terminalId")
    int updateSecretVersion(String reference,int version,UUID terminalId);
}
