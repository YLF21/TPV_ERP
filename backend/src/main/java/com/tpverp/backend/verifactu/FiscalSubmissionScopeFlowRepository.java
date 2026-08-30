package com.tpverp.backend.verifactu;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalSubmissionScopeFlowRepository
        extends JpaRepository<FiscalSubmissionScopeFlow, UUID> {
    /** Creates the unique scope row without poisoning the caller transaction on a race. */
    @Modifying
    @Query(value = """
            insert into flujo_envio_fiscal_scope
                (id, empresa_id, instalacion_id, entorno, version)
            values (:id, :companyId, :installationId, :environment, 0)
            on conflict (empresa_id, instalacion_id, entorno) do nothing
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("id") UUID id,
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("environment") String environment);

    Optional<FiscalSubmissionScopeFlow> findByCompanyIdAndInstallationIdAndEnvironment(
            UUID companyId, UUID installationId, FiscalEndpointEnvironment environment);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select flow from FiscalSubmissionScopeFlow flow
             where flow.companyId = :companyId
               and flow.installationId = :installationId
               and flow.environment = :environment
            """)
    Optional<FiscalSubmissionScopeFlow> findForUpdate(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("environment") FiscalEndpointEnvironment environment);
}
