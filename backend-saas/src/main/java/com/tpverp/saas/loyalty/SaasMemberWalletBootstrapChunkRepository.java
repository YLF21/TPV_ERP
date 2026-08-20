package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberWalletBootstrapChunkRepository
        extends JpaRepository<SaasMemberWalletBootstrapChunk, UUID> {

    Optional<SaasMemberWalletBootstrapChunk> findBySnapshot_IdAndKindAndChunkIndex(
            UUID snapshotId,
            String kind,
            int chunkIndex);

    List<SaasMemberWalletBootstrapChunk> findBySnapshot_Id(UUID snapshotId);
}
