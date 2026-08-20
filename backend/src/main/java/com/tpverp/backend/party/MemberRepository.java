package com.tpverp.backend.party;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByCustomerId(UUID customerId);

    Optional<Member> findByCustomerIdAndCompanyId(UUID customerId, UUID companyId);

    Optional<Member> findByIdAndCompanyId(UUID id, UUID companyId);

    @EntityGraph(attributePaths = {"memberCategory"})
    List<Member> findByCompanyIdAndCustomerIdIn(UUID companyId, Collection<UUID> customerIds);

    @EntityGraph(attributePaths = {"customer", "memberCategory"})
    List<Member> findByCompanyIdOrderByCustomerFiscalNameAsc(UUID companyId);

    Optional<Member> findByCompanyIdAndNumMember(UUID companyId, String numMember);

    List<Member> findByMemberCategoryId(UUID categoryId);

    @Query("""
            select member.id as memberId,
                   member.memberBalance as loyaltyBalance,
                   member.returnCreditBalance as returnCreditBalance
            from Member member
            where member.company.id = :companyId
            order by cast(member.id as string)
            """)
    Slice<MemberWalletSnapshotAccountProjection> findWalletSnapshotAccounts(
            @Param("companyId") UUID companyId,
            Pageable pageable);

    @Query("""
            select member.id as memberId,
                   member.memberPoints as points,
                   member.loyaltyPointsDebt as pointsDebt
            from Member member
            where member.company.id = :companyId
            order by cast(member.id as string)
            """)
    Slice<MemberPointsSnapshotAccountProjection> findPointsSnapshotAccounts(
            @Param("companyId") UUID companyId,
            Pageable pageable);

    interface MemberWalletSnapshotAccountProjection {
        UUID getMemberId();

        BigDecimal getLoyaltyBalance();

        BigDecimal getReturnCreditBalance();
    }

    interface MemberPointsSnapshotAccountProjection {
        UUID getMemberId();
        long getPoints();
        long getPointsDebt();
    }
}
