package com.tpverp.backend.installation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallationRepository extends JpaRepository<Installation, UUID> {

    Optional<Installation> findByReferencia(String referencia);
}
