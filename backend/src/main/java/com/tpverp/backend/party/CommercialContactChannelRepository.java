package com.tpverp.backend.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommercialContactChannelRepository extends JpaRepository<CommercialContactChannel, UUID> {

    List<CommercialContactChannel> findByCompanyIdOrderByCodeAsc(UUID companyId);

    Optional<CommercialContactChannel> findByIdAndCompanyId(UUID id, UUID companyId);
}
