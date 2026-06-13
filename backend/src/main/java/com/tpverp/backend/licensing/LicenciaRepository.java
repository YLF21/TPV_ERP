package com.tpverp.backend.licensing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenciaRepository extends JpaRepository<Licencia, UUID> {

    Optional<Licencia> findByReferencia(String referencia);

    Optional<Licencia> findByTiendaIdAndInstalacionIdAndActivaTrue(UUID tiendaId, UUID instalacionId);

    List<Licencia> findByTiendaIdOrderByValidaDesdeDesc(UUID tiendaId);
}
