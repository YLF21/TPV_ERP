package com.tpverp.saas.tenant;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasTenantUserRepository extends JpaRepository<SaasTenantUser, UUID> {

    Optional<SaasTenantUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    List<SaasTenantUser> findByCompany_IdOrderByUsernameAsc(UUID companyId);
}
