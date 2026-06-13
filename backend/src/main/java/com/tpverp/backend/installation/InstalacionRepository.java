package com.tpverp.backend.installation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstalacionRepository extends JpaRepository<Instalacion, UUID> {

    Optional<Instalacion> findByReferencia(String referencia);
}
