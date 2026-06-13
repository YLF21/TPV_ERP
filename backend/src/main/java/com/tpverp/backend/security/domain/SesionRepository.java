package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SesionRepository extends JpaRepository<Sesion, UUID> {

    Optional<Sesion> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = {
            "usuario", "usuario.rol", "usuario.rol.permisos", "usuario.rol.permisos.permiso"
    })
    Optional<Sesion> findByTokenHashAndRevocadaEnIsNull(String tokenHash);

    List<Sesion> findByUsuarioIdAndRevocadaEnIsNull(UUID usuarioId);

    List<Sesion> findByTerminalIdAndRevocadaEnIsNull(UUID terminalId);
}
