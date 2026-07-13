package com.tpverp.backend.document;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface SalePaymentSessionRepository extends JpaRepository<SalePaymentSession,UUID>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from SalePaymentSession s left join fetch s.allocations where s.id=:id")
 java.util.Optional<SalePaymentSession> findLocked(@Param("id") UUID id);
 @Query("select distinct s from SalePaymentSession s left join fetch s.allocations where s.id=:id") java.util.Optional<SalePaymentSession> findState(@Param("id") UUID id);
}
