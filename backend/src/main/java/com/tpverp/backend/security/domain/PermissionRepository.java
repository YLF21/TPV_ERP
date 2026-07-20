package com.tpverp.backend.security.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByCodigo(String codigo);

    List<Permission> findAllByOrderByGrupoAscCodigoAsc();
}
