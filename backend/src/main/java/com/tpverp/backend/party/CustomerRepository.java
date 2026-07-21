package com.tpverp.backend.party;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByCompanyIdOrderByFiscalName(UUID companyId);

    List<Customer> findByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);

    Optional<Customer> findByCompanyIdAndDocumentTypeAndDocumentNumber(
            UUID companyId, DocumentType documentType, String documentNumber);

    Optional<Customer> findByIdAndCompanyId(UUID id, UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from Customer customer where customer.id = :id and customer.company.id = :companyId")
    Optional<Customer> findLockedByIdAndCompanyId(
            @Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query(value = """
            select coalesce(sum(document.total - coalesce(payment.paid, 0)), 0)
            from documento document
            left join (
                select documento_id, sum(importe) as paid
                from documento_pago
                group by documento_id
            ) payment on payment.documento_id = document.id
            where document.cliente_id = :customerId
              and document.tipo in ('ALBARAN_VENTA','FACTURA_VENTA')
              and document.estado in ('PENDIENTE','PARCIAL')
              and not exists (
                  select 1 from documento_relacion relation
                  join documento invoice on invoice.id = relation.documento_id
                  where relation.origen_id = document.id
                    and relation.tipo = 'FACTURA_DE'
                    and invoice.estado not in ('BORRADOR','ANULADO')
              )
            """, nativeQuery = true)
    BigDecimal outstandingDebt(@Param("customerId") UUID customerId);

    @Query(value = """
            select coalesce(sum(document.total - coalesce(payment.paid, 0)), 0)
            from documento document
            left join (
                select documento_id, sum(importe) as paid
                from documento_pago
                group by documento_id
            ) payment on payment.documento_id = document.id
            where document.cliente_id = :customerId
              and document.tipo in ('ALBARAN_VENTA','FACTURA_VENTA')
              and document.estado in ('PENDIENTE','PARCIAL')
              and document.fecha_vencimiento < :businessDate
              and not exists (
                  select 1 from documento_relacion relation
                  join documento invoice on invoice.id = relation.documento_id
                  where relation.origen_id = document.id
                    and relation.tipo = 'FACTURA_DE'
                    and invoice.estado not in ('BORRADOR','ANULADO')
              )
            """, nativeQuery = true)
    BigDecimal overdueDebt(
            @Param("customerId") UUID customerId,
            @Param("businessDate") LocalDate businessDate);

    @Query(value = "select exists(select 1 from documento where cliente_id = :customerId)",
            nativeQuery = true)
    boolean hasDocumentHistory(UUID customerId);
}
