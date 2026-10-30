package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = {
            "usuario", "usuario.tienda", "usuario.tienda.empresa",
            "usuario.rol", "usuario.rol.permisos", "usuario.rol.permisos.permiso",
            "terminal", "terminal.tienda", "terminal.tienda.empresa"
    })
    Optional<UserSession> findByTokenHashAndRevocadaEnIsNull(String tokenHash);

    List<UserSession> findByUsuarioIdAndRevocadaEnIsNull(UUID usuarioId);

    List<UserSession> findByTerminalIdAndRevocadaEnIsNull(UUID terminalId);

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Transactional
    @Query(value = """
            update sesion
               set ultima_actividad_en = :now, version = version + 1
             where id = :id and revocada_en is null and ultima_actividad_en <= :threshold
            """, nativeQuery = true)
    int touchActivity(@Param("id") UUID id, @Param("now") Instant now,
            @Param("threshold") Instant threshold);
}
