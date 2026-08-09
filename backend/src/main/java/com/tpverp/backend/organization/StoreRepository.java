package com.tpverp.backend.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    List<Store> findByEmpresaId(UUID empresaId);

    boolean existsByEmpresaIdAndCodigoTienda(UUID empresaId, String codigoTienda);

    Optional<Store> findByEmpresaIdAndAddressNormalizedHash(UUID empresaId, String addressNormalizedHash);

    @Query("""
            select store
            from Store store
            join fetch store.empresa
            where store.id = :storeId
            """)
    Optional<Store> findWithCompanyById(UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select store
            from Store store
            join fetch store.empresa
            where store.id = :storeId
            """)
    Optional<Store> findByIdForUpdate(UUID storeId);
}
