package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberPointsBootstrapChunkRepository extends JpaRepository<SaasMemberPointsBootstrapChunk, UUID> {
    Optional<SaasMemberPointsBootstrapChunk> findBySnapshot_IdAndKindAndChunkIndex(UUID snapshotId, String kind, int index);
    List<SaasMemberPointsBootstrapChunk> findBySnapshot_Id(UUID snapshotId);
}
