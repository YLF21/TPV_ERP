package com.tpverp.backend.document;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface SalePaymentSessionRepository extends JpaRepository<SalePaymentSession,UUID>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from SalePaymentSession s left join fetch s.allocations where s.id=:id")
 java.util.Optional<SalePaymentSession> findLocked(@Param("id") UUID id);
 @Query("select distinct s from SalePaymentSession s left join fetch s.allocations where s.id=:id") java.util.Optional<SalePaymentSession> findState(@Param("id") UUID id);
 @Query("select distinct s from SalePaymentSession s left join fetch s.allocations where s.storeId=:storeId and s.terminalId=:terminalId and s.userId=:userId and s.status in (com.tpverp.backend.document.SalePaymentSessionStatus.COLLECTING,com.tpverp.backend.document.SalePaymentSessionStatus.COVERED,com.tpverp.backend.document.SalePaymentSessionStatus.COMPENSATION_REQUIRED)") java.util.Optional<SalePaymentSession> findActive(@Param("storeId") UUID storeId,@Param("terminalId") UUID terminalId,@Param("userId") UUID userId);
 @Query("""
         select allocation
           from SalePaymentAllocation allocation
           join allocation.session session
          where allocation.originalPaymentId in :originalPaymentIds
            and session.status in (
                com.tpverp.backend.document.SalePaymentSessionStatus.COLLECTING,
                com.tpverp.backend.document.SalePaymentSessionStatus.COVERED,
                com.tpverp.backend.document.SalePaymentSessionStatus.COMPENSATION_REQUIRED)
            and allocation.status not in (
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.CANCELLED,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.DECLINED,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.ERROR)
         """)
 List<SalePaymentAllocation> findActiveRefundReservations(
         @Param("originalPaymentIds") Collection<UUID> originalPaymentIds);
}
