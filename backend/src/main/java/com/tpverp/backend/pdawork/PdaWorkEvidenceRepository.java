package com.tpverp.backend.pdawork;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface PdaWorkEvidenceRepository extends JpaRepository<PdaWorkEvidence,UUID>{
    List<PdaWorkEvidence> findByWorkIdOrderByCreatedAt(UUID workId);
    long countByWorkId(UUID workId);

    @Query("""
            select evidence.id as id, evidence.workId as workId, evidence.name as name,
                   evidence.contentType as contentType, evidence.size as size,
                   evidence.storageReference as storageReference, evidence.createdAt as createdAt,
                   evidence.version as version
              from PdaWorkEvidence evidence
             where evidence.workId in :workIds
             order by evidence.createdAt
            """)
    List<Metadata> findMetadataByWorkIdIn(@Param("workIds") Collection<UUID> workIds);

    interface Metadata {
        UUID getId();
        UUID getWorkId();
        String getName();
        String getContentType();
        long getSize();
        String getStorageReference();
        java.time.Instant getCreatedAt();
        long getVersion();
    }
}
