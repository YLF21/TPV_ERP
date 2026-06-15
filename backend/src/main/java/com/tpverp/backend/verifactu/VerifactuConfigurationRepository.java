package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerifactuConfigurationRepository
        extends JpaRepository<VerifactuConfiguration, UUID> {

    Optional<VerifactuConfiguration> findByCompanyId(UUID companyId);

    @Modifying
    @Query(value = """
            insert into configuracion_verifactu (
                id, empresa_id, activacion_voluntaria, version)
            values (:id, :companyId, false, 0)
            on conflict (empresa_id) do nothing
            """, nativeQuery = true)
    void insertIfMissing(
            @Param("id") UUID id,
            @Param("companyId") UUID companyId);
}
