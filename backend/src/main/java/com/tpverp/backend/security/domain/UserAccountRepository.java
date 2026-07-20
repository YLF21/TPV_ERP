package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTiendaIdAndNombre(UUID tiendaId, String nombre);

    Optional<UserAccount> findByNombreAndTiendaIsNull(String nombre);

    @Query("""
            select user from UserAccount user
            where user.tienda.empresa.id = :companyId and user.nombre = :nombre
            """)
    Optional<UserAccount> findByEmpresaIdAndNombre(
            @Param("companyId") UUID companyId, @Param("nombre") String nombre);

    Optional<UserAccount> findByIdAndTiendaId(UUID id, UUID tiendaId);

    @Query("""
            select user from UserAccount user
            where user.id = :id and user.tienda.empresa.id = :companyId
            """)
    Optional<UserAccount> findByIdAndEmpresaId(@Param("id") UUID id, @Param("companyId") UUID companyId);

    List<UserAccount> findAllByTiendaIdOrderByNombre(UUID tiendaId);

    @Query("""
            select user from UserAccount user
            where user.tienda.empresa.id = :companyId
            order by user.nombre
            """)
    List<UserAccount> findAllByEmpresaIdOrderByNombre(@Param("companyId") UUID companyId);

    long countByRolId(UUID roleId);

    @Query(value = """
            select exists (
                select 1 from usuario_tienda access
                where access.usuario_id = :userId and access.tienda_id = :storeId
            )
            """, nativeQuery = true)
    boolean hasStoreAccess(@Param("userId") UUID userId, @Param("storeId") UUID storeId);
}
