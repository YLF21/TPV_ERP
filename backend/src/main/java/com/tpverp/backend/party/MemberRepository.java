package com.tpverp.backend.party;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByCustomerId(UUID customerId);

    Optional<Member> findByCustomerIdAndCompanyId(UUID customerId, UUID companyId);

    Optional<Member> findByIdAndCompanyId(UUID id, UUID companyId);

    @EntityGraph(attributePaths = {"customer", "memberCategory"})
    List<Member> findByCompanyIdOrderByCustomerFiscalNameAsc(UUID companyId);

    Optional<Member> findByCompanyIdAndNumMember(UUID companyId, String numMember);

    List<Member> findByMemberCategoryId(UUID categoryId);
}
