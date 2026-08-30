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
         select session from SalePaymentSession session
          where session.memberBalanceReservationId in :reservationIds
            and (session.status in (
                com.tpverp.backend.document.SalePaymentSessionStatus.COLLECTING,
                com.tpverp.backend.document.SalePaymentSessionStatus.COVERED,
                com.tpverp.backend.document.SalePaymentSessionStatus.COMPENSATION_REQUIRED)
                 or (session.status = com.tpverp.backend.document.SalePaymentSessionStatus.FINALIZED
                     and session.ticketId is not null))
         """)
 java.util.List<SalePaymentSession> findBlockingMemberBalanceSessionsByReservationIds(
         @Param("reservationIds") java.util.Collection<java.util.UUID> reservationIds);
 java.util.Optional<SalePaymentSession> findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(
         @Param("reservationId") java.util.UUID reservationId);
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
 @Query("""
         select session from SalePaymentSession session
          where session.ticketId is not null
            and session.memberBalanceReservationId is not null
            and session.memberBalanceSynchronizedAt is null
            and session.memberBalanceRecoveryManualReview = false
            and (session.memberBalanceRecoveryNextAttemptAt is null
                 or session.memberBalanceRecoveryNextAttemptAt <= :now)
          order by session.updatedAt asc
         """)
 List<SalePaymentSession> findMemberBalanceFinalizationRecoveryCandidates(
         @Param("now") java.time.Instant now,
         org.springframework.data.domain.Pageable pageable);

 @Query("""
         select session from SalePaymentSession session
          where session.status = com.tpverp.backend.document.SalePaymentSessionStatus.CANCELLED
            and session.ticketId is null
            and session.memberBalanceReservationId is not null
            and session.memberBalanceSynchronizedAt is null
            and session.memberBalanceRecoveryManualReview = false
            and (session.memberBalanceRecoveryNextAttemptAt is null
                 or session.memberBalanceRecoveryNextAttemptAt <= :now)
          order by session.updatedAt asc
         """)
 List<SalePaymentSession> findMemberBalanceAbortRecoveryCandidates(
         @Param("now") java.time.Instant now,
         org.springframework.data.domain.Pageable pageable);

 @Query("""
         select session from SalePaymentSession session
          where session.storeId = :storeId
            and session.memberBalanceReservationId is not null
            and session.memberBalanceSynchronizedAt is null
            and (session.ticketId is not null
                 or session.status = com.tpverp.backend.document.SalePaymentSessionStatus.CANCELLED)
          order by session.updatedAt asc
         """)
 List<SalePaymentSession> findMemberBalanceRecoveryIncidents(
         @Param("storeId") UUID storeId,
         org.springframework.data.domain.Pageable pageable);

 @Query("""
         select session.ticketId as ticketId,
                session.ticketNumber as ticketNumber,
                session.memberBalanceAppliedAmount as amount
           from SalePaymentSession session
          where session.storeId = :storeId
            and session.status = com.tpverp.backend.document.SalePaymentSessionStatus.FINALIZED
            and session.memberBalanceAppliedAmount is not null
            and session.ticketId in :ticketIds
         """)
 List<MemberBalanceReportTotal> findFinalizedMemberBalanceTotalsByTicketIds(
         @Param("storeId") UUID storeId,
         @Param("ticketIds") Collection<UUID> ticketIds);

 @Query("""
         select session.ticketId as ticketId,
                session.ticketNumber as ticketNumber,
                session.memberBalanceAppliedAmount as amount
           from SalePaymentSession session
          where session.storeId = :storeId
            and session.status = com.tpverp.backend.document.SalePaymentSessionStatus.FINALIZED
            and session.memberBalanceAppliedAmount is not null
            and session.ticketNumber in :ticketNumbers
         """)
 List<MemberBalanceReportTotal> findFinalizedMemberBalanceTotalsByTicketNumbers(
         @Param("storeId") UUID storeId,
         @Param("ticketNumbers") Collection<String> ticketNumbers);

 interface MemberBalanceReportTotal {
  UUID getTicketId();
  String getTicketNumber();
  java.math.BigDecimal getAmount();
 }
}
