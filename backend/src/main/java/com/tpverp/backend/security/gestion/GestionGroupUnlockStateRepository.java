package com.tpverp.backend.security.gestion;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GestionGroupUnlockStateRepository
        extends JpaRepository<GestionGroupUnlockState, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select state from GestionGroupUnlockState state
            join fetch state.session session
            where session.id = :sessionId and state.group = :group
            """)
    Optional<GestionGroupUnlockState> findLocked(
            @Param("sessionId") UUID sessionId,
            @Param("group") GestionGroup group);

    @Query("""
            select state from GestionGroupUnlockState state
            where state.session.id = :sessionId and state.group = :group
            """)
    Optional<GestionGroupUnlockState> findState(
            @Param("sessionId") UUID sessionId,
            @Param("group") GestionGroup group);
}
