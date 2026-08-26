package com.tpverp.backend.licensing;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select license from License license
            where license.tienda.id = :tiendaId
              and license.instalacion.id = :instalacionId
              and license.activa = true
            """)
    Optional<License> findActiveForSaasValidationForUpdate(
            @Param("tiendaId") UUID tiendaId,
            @Param("instalacionId") UUID instalacionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select license from License license where license.id = :licenseId")
    Optional<License> findByIdForSaasValidationForUpdate(
            @Param("licenseId") UUID licenseId);

    @EntityGraph(attributePaths = {"tienda", "tienda.empresa"})
    List<License> findByInstalacion_IdAndActivaTrue(UUID instalacionId);

    @Query("""
            select license from License license
            where license.tienda.id = :tiendaId
            order by license.validaDesde desc
            """)
    List<License> findByTiendaIdOrderByValidaDesdeDesc(@Param("tiendaId") UUID tiendaId);

    Optional<License> findFirstByTienda_IdAndActivaTrueOrderByValidaDesdeDesc(UUID tiendaId);

    @EntityGraph(attributePaths = {"tienda", "tienda.empresa"})
    @Query("""
            select license from License license
            where license.tienda.id = :tiendaId
              and license.activa = true
            order by license.validaDesde desc
            """)
    List<License> findActiveByTiendaId(@Param("tiendaId") UUID tiendaId);

    @EntityGraph(attributePaths = {"tienda", "tienda.empresa"})
    @Query("""
            select license from License license
            where license.tienda.empresa.id = :companyId
              and license.activa = true
            order by license.validaDesde desc
            """)
    List<License> findActiveByCompanyId(@Param("companyId") UUID companyId);

    @Query("""
            select distinct license.tienda.timezone
            from License license
            where license.tienda.empresa.id = :companyId
              and license.instalacion.id = :installationId
              and license.activa = true
            """)
    List<String> findActiveStoreTimezonesByCompanyIdAndInstallationId(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId);

    @EntityGraph(attributePaths = {"tienda", "tienda.empresa"})
    @Query("""
            select license from License license
            where license.tienda.empresa.id = :companyId
              and license.instalacion.id = :installationId
              and license.activa = true
            order by license.validaDesde desc
            """)
    List<License> findActiveByCompanyIdAndInstallationId(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId);

    @EntityGraph(attributePaths = {"tienda", "tienda.empresa"})
    List<License> findByActivaTrueOrderByValidaDesdeDesc();
}
