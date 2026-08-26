package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FiscalRecordArtifactRepository
        extends JpaRepository<FiscalRecordArtifact, UUID> {

    Optional<FiscalRecordArtifact> findByRecordId(UUID recordId);

    /** Bounded batch read for integrity checks; callers must keep the batch small. */
    List<FiscalRecordArtifact> findAllByRecordIdIn(Collection<UUID> recordIds);

    @Query("select coalesce(a.signedXml, a.unsignedXml) from FiscalRecordArtifact a "
            + "where a.recordId = :recordId")
    Optional<String> findFrozenXmlByRecordId(UUID recordId);
}
