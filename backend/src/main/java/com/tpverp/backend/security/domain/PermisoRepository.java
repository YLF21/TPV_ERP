package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermisoRepository extends JpaRepository<Permiso, UUID> {

    Optional<Permiso> findByCodigo(String codigo);
}
