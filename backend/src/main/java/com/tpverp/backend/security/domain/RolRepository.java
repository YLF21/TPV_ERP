package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolRepository extends JpaRepository<Rol, UUID> {

    Optional<Rol> findByTiendaIdAndNombre(UUID tiendaId, String nombre);
}
