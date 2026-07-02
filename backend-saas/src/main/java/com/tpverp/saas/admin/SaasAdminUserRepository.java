package com.tpverp.saas.admin;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SaasAdminUserRepository extends JpaRepository<SaasAdminUser, UUID> {

    Optional<SaasAdminUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    @Query(value = """
            select distinct permission_code
            from saas_admin_user u
            join saas_admin_user_role ur on ur.user_id = u.id
            join saas_admin_role_permission rp on rp.role_id = ur.role_id
            where lower(u.username) = lower(?1)
            """, nativeQuery = true)
    Set<String> permissionCodes(String username);
}
