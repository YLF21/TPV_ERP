package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface SubfamilyRepository extends JpaRepository<Subfamily, UUID> {

    List<Subfamily> findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(UUID familyId);

    @Query("select subfamily from Subfamily subfamily join Family family "
            + "on family.id = subfamily.familyId "
            + "where family.storeId = :storeId and subfamily.id in :ids")
    List<Subfamily> findByStoreIdAndIdIn(UUID storeId, java.util.Collection<UUID> ids);

    @Query("select subfamily from Subfamily subfamily join Family family "
            + "on family.id = subfamily.familyId "
            + "where family.storeId = :storeId and subfamily.id = :id")
    Optional<Subfamily> findByStoreIdAndId(UUID storeId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select subfamily from Subfamily subfamily where subfamily.id = :id")
    Optional<Subfamily> findByIdForUpdate(UUID id);

    boolean existsByFamilyIdAndNombreIgnoreCase(UUID familyId, String nombre);

    boolean existsByFamilyIdAndNombreIgnoreCaseAndIdNot(UUID familyId, String nombre, UUID id);

    @Query("select subfamily from Subfamily subfamily where subfamily.familyId = :familyId "
            + "and upper(subfamily.subfamilySuffix) = upper(:suffix)")
    Optional<Subfamily> findByFamilyIdAndSubfamilySuffix(UUID familyId, String suffix);

    @Query("select subfamily from Subfamily subfamily join Family family "
            + "on family.id = subfamily.familyId "
            + "where family.storeId = :storeId and upper(subfamily.subfamilyCode) = upper(:code)")
    Optional<Subfamily> findByStoreIdAndSubfamilyCode(UUID storeId, String code);

    @Query(value = "select subfamily_suffix from subfamilia_codigo_reservado where familia_id = :familyId",
            nativeQuery = true)
    List<String> findReservedSubfamilySuffixes(UUID familyId);
}
