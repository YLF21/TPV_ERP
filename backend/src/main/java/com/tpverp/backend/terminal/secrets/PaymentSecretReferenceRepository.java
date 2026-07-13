package com.tpverp.backend.terminal.secrets;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface PaymentSecretReferenceRepository extends JpaRepository<PaymentSecretReference, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PaymentSecretReference s where s.opaqueReference=:reference and s.companyId=:companyId and s.storeId=:storeId and s.terminalId=:terminalId and s.active=true")
    Optional<PaymentSecretReference> findActiveForUpdate(String reference,UUID companyId,UUID storeId,UUID terminalId);
    @Query("select s from PaymentSecretReference s where s.opaqueReference=:reference and s.companyId=:companyId and s.storeId=:storeId and s.terminalId=:terminalId and s.active=true")
    Optional<PaymentSecretReference> findActiveScoped(String reference,UUID companyId,UUID storeId,UUID terminalId);
}
