package com.tpverp.backend.pdawork;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PdaWorkEvidenceRepository extends JpaRepository<PdaWorkEvidence,UUID>{
    List<PdaWorkEvidence> findByWorkIdOrderByCreatedAt(UUID workId);
}
