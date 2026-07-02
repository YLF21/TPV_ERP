package com.tpverp.backend.party;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByCustomerId(UUID customerId);

    Optional<Member> findByCustomerIdAndCompanyId(UUID customerId, UUID companyId);

    Optional<Member> findByCompanyIdAndNumMember(UUID companyId, String numMember);
}
