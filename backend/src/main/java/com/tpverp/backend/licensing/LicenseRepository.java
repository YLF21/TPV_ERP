package com.tpverp.backend.licensing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LicenseRepository extends JpaRepository<License, UUID> {

    Optional<License> findByReferencia(String referencia);

    @Query("""
            select license from License license
            where license.tienda.id = :tiendaId
              and license.instalacion.id = :instalacionId
              and license.activa = true
            """)
    Optional<License> findByTiendaIdAndInstalacionIdAndActivaTrue(
            @Param("tiendaId") UUID tiendaId,
            @Param("instalacionId") UUID instalacionId);

    @Query("""
            select license from License license
            where license.tienda.id = :tiendaId
            order by license.validaDesde desc
            """)
    List<License> findByTiendaIdOrderByValidaDesdeDesc(@Param("tiendaId") UUID tiendaId);
}
