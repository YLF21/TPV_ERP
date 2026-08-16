package com.tpverp.backend.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    Optional<Voucher> findByTiendaIdAndCode(UUID tiendaId, String code);

    Optional<Voucher> findByTiendaIdAndCodeIgnoreCase(UUID tiendaId, String code);

    @Query("""
            select voucher
              from Voucher voucher
              join fetch voucher.family family
             where family.companyId = :companyId
               and lower(voucher.code) = lower(:code)
            """)
    Optional<Voucher> findByCompanyIdAndCodeIgnoreCase(
            @Param("companyId") UUID companyId, @Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select voucher from Voucher voucher where voucher.tiendaId = :storeId and voucher.code = :code")
    Optional<Voucher> findLockedByTiendaIdAndCode(
            @Param("storeId") UUID tiendaId, @Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select voucher
              from Voucher voucher
              join fetch voucher.family family
             where family.companyId = :companyId
               and lower(voucher.code) = lower(:code)
            """)
    Optional<Voucher> findLockedByCompanyIdAndCode(
            @Param("companyId") UUID companyId, @Param("code") String code);

    List<Voucher> findAllByTiendaIdOrderByCreatedAtDesc(UUID tiendaId);

    @Query("""
            select voucher
              from Voucher voucher
              join fetch voucher.family family
             where family.companyId = :companyId
             order by voucher.createdAt desc, voucher.id desc
            """)
    List<Voucher> findAllByCompanyIdOrderByCreatedAtDesc(
            @Param("companyId") UUID companyId);

    @Query(value = """
            select v.*
              from vale v
              join vale_familia f on f.id = v.familia_id
             where v.tienda_id = :storeId
               and (cast(:query as text) is null
                    or lower(v.codigo) like lower(concat('%', cast(:query as text), '%'))
                    or lower(f.identificador) like lower(concat('%', cast(:query as text), '%'))
                    or cast(v.tickets_origen as text) ilike concat('%', cast(:query as text), '%'))
               and (cast(:fromInstant as timestamptz) is null
                    or v.creado_en >= cast(:fromInstant as timestamptz))
               and (cast(:untilInstant as timestamptz) is null
                    or v.creado_en < cast(:untilInstant as timestamptz))
               and (
                    cast(:effectiveStatus as text) is null
                    or (:effectiveStatus = 'ACTIVE' and v.status = 'ACTIVE'
                        and (v.caduca_el is null or v.caduca_el >= cast(:today as date)))
                    or (:effectiveStatus = 'EXPIRED' and v.status = 'ACTIVE'
                        and v.caduca_el is not null and v.caduca_el < cast(:today as date))
                    or (:effectiveStatus = 'CONSUMED' and v.status = 'CONSUMED')
                    or (:effectiveStatus = 'INVALIDATED' and v.status = 'INVALIDATED')
               )
             order by v.creado_en desc, v.id desc
            """,
            countQuery = """
            select count(*)
              from vale v
              join vale_familia f on f.id = v.familia_id
             where v.tienda_id = :storeId
               and (cast(:query as text) is null
                    or lower(v.codigo) like lower(concat('%', cast(:query as text), '%'))
                    or lower(f.identificador) like lower(concat('%', cast(:query as text), '%'))
                    or cast(v.tickets_origen as text) ilike concat('%', cast(:query as text), '%'))
               and (cast(:fromInstant as timestamptz) is null
                    or v.creado_en >= cast(:fromInstant as timestamptz))
               and (cast(:untilInstant as timestamptz) is null
                    or v.creado_en < cast(:untilInstant as timestamptz))
               and (
                    cast(:effectiveStatus as text) is null
                    or (:effectiveStatus = 'ACTIVE' and v.status = 'ACTIVE'
                        and (v.caduca_el is null or v.caduca_el >= cast(:today as date)))
                    or (:effectiveStatus = 'EXPIRED' and v.status = 'ACTIVE'
                        and v.caduca_el is not null and v.caduca_el < cast(:today as date))
                    or (:effectiveStatus = 'CONSUMED' and v.status = 'CONSUMED')
                    or (:effectiveStatus = 'INVALIDATED' and v.status = 'INVALIDATED')
               )
            """,
            nativeQuery = true)
    Page<Voucher> findManagementPage(
            @Param("storeId") UUID storeId,
            @Param("query") String query,
            @Param("effectiveStatus") String effectiveStatus,
            @Param("fromInstant") Instant fromInstant,
            @Param("untilInstant") Instant untilInstant,
            @Param("today") LocalDate today,
            Pageable pageable);

    @Query(value = """
            select *
              from vale
             where tienda_id = :storeId
               and tickets_origen @> jsonb_build_array(cast(:ticketNumber as text))
             order by creado_en
            """, nativeQuery = true)
    List<Voucher> findAllByOriginTicket(
            @Param("storeId") UUID storeId,
            @Param("ticketNumber") String ticketNumber);

    @Query(value = """
            select *
              from vale
             where tienda_id = :storeId
               and tickets_origen @> jsonb_build_array(cast(:ticketNumber as text))
             order by creado_en
             for update
            """, nativeQuery = true)
    List<Voucher> findAllLockedByOriginTicket(
            @Param("storeId") UUID storeId,
            @Param("ticketNumber") String ticketNumber);
}
