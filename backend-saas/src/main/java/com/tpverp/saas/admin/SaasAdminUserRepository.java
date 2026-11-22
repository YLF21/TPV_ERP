package com.tpverp.saas.admin;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SaasAdminUserRepository extends JpaRepository<SaasAdminUser, UUID> {

    Optional<SaasAdminUser> findByUsernameIgnoreCase(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from SaasAdminUser u where lower(u.username) = lower(?1)")
    Optional<SaasAdminUser> lockByUsername(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByActiveTrueAndIdNot(UUID id);
}
