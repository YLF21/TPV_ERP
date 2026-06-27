package com.tpverp.backend.licensing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseRepository extends JpaRepository<License, UUID> {

    Optional<License> findByReferencia(String referencia);

    Optional<License> findByTiendaIdAndInstalacionIdAndActivaTrue(UUID tiendaId, UUID instalacionId);

    List<License> findByTiendaIdOrderByValidaDesdeDesc(UUID tiendaId);
}
