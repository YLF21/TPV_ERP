package com.tpverp.backend.verifactu;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalChainRepository extends JpaRepository<FiscalChain, UUID> {

    @Modifying
    @Query(value = """
            insert into cadena_fiscal (
                id, empresa_id, instalacion_id, ultima_secuencia, actualizada_en)
            values (:id, :companyId, :installationId, 0, :createdAt)
            on conflict (empresa_id, instalacion_id) do nothing
            """, nativeQuery = true)
    void insertIfMissing(
            @Param("id") UUID id,
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("createdAt") Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select chain from FiscalChain chain
            where chain.companyId = :companyId
              and chain.installationId = :installationId
            """)
    Optional<FiscalChain> findForUpdate(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId);
}
