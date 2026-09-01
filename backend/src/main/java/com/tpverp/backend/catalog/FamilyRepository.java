package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface FamilyRepository extends JpaRepository<Family, UUID> {

    List<Family> findByStoreIdOrderByFamilyCodeAscIdAsc(UUID storeId);

    List<Family> findByStoreIdAndIdIn(UUID storeId, java.util.Collection<UUID> ids);

    boolean existsByStoreIdAndNombreIgnoreCase(UUID storeId, String nombre);

    boolean existsByStoreIdAndNombreIgnoreCaseAndIdNot(UUID storeId, String nombre, UUID id);

    @Query("select family from Family family where family.storeId = :storeId "
            + "and upper(family.familyCode) = upper(:familyCode)")
    Optional<Family> findByStoreIdAndFamilyCode(UUID storeId, String familyCode);

    Optional<Family> findByStoreIdAndId(UUID storeId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select family from Family family where family.id = :id")
    Optional<Family> findByIdForUpdate(UUID id);

    @Query(value = "select family_code from familia_codigo_reservado where tienda_id = :storeId",
            nativeQuery = true)
    List<String> findReservedFamilyCodes(UUID storeId);

    Optional<Family> findByStoreIdAndPredeterminadaTrue(UUID storeId);

    @Query(value = """
            select result.kind, result.id, result.family_id as familyId,
                   result.subfamily_id as subfamilyId, result.code, result.name,
                   result.family_code as familyCode, result.suffix,
                   result.default_family as defaultFamily
            from (
                select 'FAMILY' as kind, f.id, f.id as family_id,
                       cast(null as uuid) as subfamily_id, f.family_code as code,
                       f.nombre as name, f.family_code, cast(null as varchar) as suffix,
                       f.predeterminada as default_family
                from familia f
                where f.tienda_id = :storeId
                  and ((:termLength = 2
                        and tpv_catalog_search_normalize(f.nombre) like :term || '%' escape '\\')
                       or (:termLength >= 3
                        and tpv_catalog_search_normalize(f.nombre) like '%' || :term || '%' escape '\\')
                       or f.family_code like :term || '%' escape '\\')
                union all
                select 'SUBFAMILY' as kind, sf.id, sf.familia_id as family_id,
                       sf.id as subfamily_id, sf.subfamily_code as code,
                       sf.nombre as name, f.family_code, sf.subfamily_suffix as suffix,
                       false as default_family
                from subfamilia sf
                join familia f on f.id = sf.familia_id
                where f.tienda_id = :storeId
                  and ((:termLength = 2
                        and tpv_catalog_search_normalize(sf.nombre) like :term || '%' escape '\\')
                       or (:termLength >= 3
                        and tpv_catalog_search_normalize(sf.nombre) like '%' || :term || '%' escape '\\')
                       or sf.subfamily_code like :term || '%' escape '\\')
            ) result
            where (:cursorKind is null
                   or result.kind > :cursorKind
                   or (result.kind = :cursorKind and
                       (result.code > :cursorCode
                        or (result.code = :cursorCode and result.id > :cursorId))))
            order by result.kind, result.code, result.id
            limit :limit
            """, nativeQuery = true)
    List<FamilyHierarchySearchProjection> searchHierarchy(
            @Param("storeId") UUID storeId,
            @Param("term") String term,
            @Param("termLength") int termLength,
            @Param("cursorKind") String cursorKind,
            @Param("cursorCode") String cursorCode,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);
}
