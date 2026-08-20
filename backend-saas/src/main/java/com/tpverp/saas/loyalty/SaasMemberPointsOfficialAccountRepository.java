package com.tpverp.saas.loyalty;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface SaasMemberPointsOfficialAccountRepository extends JpaRepository<SaasMemberPointsOfficialAccount, UUID> {
    List<SaasMemberPointsOfficialAccount> findByBootstrapIdOrderByMemberIdAsc(UUID bootstrapId);

    @Query(
            value = """
                    select *
                    from saas_member_points_official_account
                    where bootstrap_id = :bootstrapId
                      and company_id = :companyId
                    order by member_id::text asc
                    """,
            countQuery = """
                    select count(*)
                    from saas_member_points_official_account
                    where bootstrap_id = :bootstrapId
                      and company_id = :companyId
                    """,
            nativeQuery = true)
    Page<SaasMemberPointsOfficialAccount> findCanonicalPage(
            @Param("bootstrapId") UUID bootstrapId,
            @Param("companyId") UUID companyId,
            Pageable pageable);
}
