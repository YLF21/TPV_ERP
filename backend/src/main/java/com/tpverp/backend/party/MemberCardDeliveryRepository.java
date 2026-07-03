package com.tpverp.backend.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberCardDeliveryRepository extends JpaRepository<MemberCardDelivery, UUID> {

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
}
