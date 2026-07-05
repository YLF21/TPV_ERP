package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = {
            "usuario", "usuario.rol", "usuario.rol.permisos", "usuario.rol.permisos.permiso", "terminal"
    })
    Optional<UserSession> findByTokenHashAndRevocadaEnIsNull(String tokenHash);

    List<UserSession> findByUsuarioIdAndRevocadaEnIsNull(UUID usuarioId);

    List<UserSession> findByTerminalIdAndRevocadaEnIsNull(UUID terminalId);
}
