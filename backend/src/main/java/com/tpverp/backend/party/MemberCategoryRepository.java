package com.tpverp.backend.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberCategoryRepository extends JpaRepository<MemberCategory, UUID> {

    List<MemberCategory> findByCompanyIdOrderBySortOrderAscMinPointsAscNameAsc(UUID companyId);

    List<MemberCategory> findByCompanyIdAndActiveTrueOrderByMinPointsDesc(UUID companyId);

    List<MemberCategory> findByCompanyIdAndActiveTrueOrderByMinPointsAscNameAsc(UUID companyId);

    Optional<MemberCategory> findByIdAndCompanyId(UUID id, UUID companyId);
}
