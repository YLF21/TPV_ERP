package com.tpverp.backend.document;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface SalePaymentSessionRepository extends JpaRepository<SalePaymentSession,UUID>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from SalePaymentSession s left join fetch s.allocations where s.id=:id")
 java.util.Optional<SalePaymentSession> findLocked(@Param("id") UUID id);
 @Query("select distinct s from SalePaymentSession s left join fetch s.allocations where s.id=:id") java.util.Optional<SalePaymentSession> findState(@Param("id") UUID id);
 @Query("select distinct s from SalePaymentSession s left join fetch s.allocations where s.storeId=:storeId and s.terminalId=:terminalId and s.userId=:userId and s.status in (com.tpverp.backend.document.SalePaymentSessionStatus.COLLECTING,com.tpverp.backend.document.SalePaymentSessionStatus.COVERED,com.tpverp.backend.document.SalePaymentSessionStatus.COMPENSATION_REQUIRED)") java.util.Optional<SalePaymentSession> findActive(@Param("storeId") UUID storeId,@Param("terminalId") UUID terminalId,@Param("userId") UUID userId);
}
