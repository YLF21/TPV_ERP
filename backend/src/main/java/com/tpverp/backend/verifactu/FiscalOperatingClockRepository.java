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

public interface FiscalOperatingClockRepository extends JpaRepository<FiscalOperatingClock, UUID> {
    @Modifying
    @Query(value = """
            insert into reloj_operativo_fiscal (
                id, empresa_id, instalacion_id, observado_en, segundos_desde_resumen, version)
            values (:id, :companyId, :installationId, :observedAt, 0, 0)
            on conflict (empresa_id, instalacion_id) do nothing
            """, nativeQuery = true)
    void insertIfMissing(@Param("id") UUID id, @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId, @Param("observedAt") Instant observedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select clock from FiscalOperatingClock clock
            where clock.companyId = :companyId and clock.installationId = :installationId
            """)
    Optional<FiscalOperatingClock> findForUpdate(@Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId);
}
