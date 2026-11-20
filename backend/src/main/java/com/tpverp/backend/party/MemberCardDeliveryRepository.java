package com.tpverp.backend.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberCardDeliveryRepository extends JpaRepository<MemberCardDelivery, UUID> {

    @Query(value = """
            select * from member_card_delivery
            where status = 'PENDIENTE' and (next_attempt_at is null or next_attempt_at <= :now)
            order by created_at, id limit 1 for update skip locked
            """, nativeQuery = true)
    Optional<MemberCardDelivery> claimNext(@Param("now") Instant now);

    List<MemberCardDelivery> findByStatusAndClaimedAtBefore(MemberCardDeliveryStatus status, Instant before);

    boolean existsByMemberId(UUID memberId);

    Optional<MemberCardDelivery> findByMemberIdAndResendRequestId(UUID memberId, UUID requestId);

    boolean existsByMemberIdAndStatusIn(UUID memberId, List<MemberCardDeliveryStatus> statuses);

    @Query(value = "select id from miembro where id = :memberId for update", nativeQuery = true)
    UUID lockMember(@Param("memberId") UUID memberId);

    List<MemberCardDelivery> findByStatusOrderByCreatedAtAsc(MemberCardDeliveryStatus status);

    @Query("""
            select delivery from MemberCardDelivery delivery
            where delivery.id = :id
              and delivery.member.company.id = :companyId
            """)
    Optional<MemberCardDelivery> findByIdAndCompanyId(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("""
            select delivery from MemberCardDelivery delivery
            where delivery.member.company.id = :companyId
            order by delivery.createdAt desc
            """)
    List<MemberCardDelivery> findByCompanyId(@Param("companyId") UUID companyId);

    @Query("""
            select delivery from MemberCardDelivery delivery
            where delivery.member.company.id = :companyId
              and delivery.status = :status
            order by delivery.createdAt desc
            """)
    List<MemberCardDelivery> findByCompanyIdAndStatus(
            @Param("companyId") UUID companyId,
            @Param("status") MemberCardDeliveryStatus status);

    @Query("""
            select delivery from MemberCardDelivery delivery
            where delivery.member.company.id = :companyId
              and delivery.member.id = :memberId
            order by delivery.createdAt desc
            """)
    List<MemberCardDelivery> findByCompanyIdAndMemberId(
            @Param("companyId") UUID companyId,
            @Param("memberId") UUID memberId);

    @Query("""
            select delivery from MemberCardDelivery delivery
            where delivery.member.company.id = :companyId
              and delivery.member.id = :memberId
              and delivery.status = :status
            order by delivery.createdAt desc
            """)
    List<MemberCardDelivery> findByCompanyIdAndMemberIdAndStatus(
            @Param("companyId") UUID companyId,
            @Param("memberId") UUID memberId,
            @Param("status") MemberCardDeliveryStatus status);
}
