package com.tpverp.saas.tenant;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SaasTenantUserRepository extends JpaRepository<SaasTenantUser, UUID> {

    Optional<SaasTenantUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    @Query(value = """
            select u.* from saas_tenant_user u join saas_tenant_company_access a on a.user_id = u.id
            where a.company_id = :companyId order by u.username
            """, nativeQuery = true)
    List<SaasTenantUser> findByCompany_IdOrderByUsernameAsc(UUID companyId);
}
