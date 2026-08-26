package com.tpverp.backend.verifactu;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LegacyFiscalArtifactIdentityRepository
        extends JpaRepository<LegacyFiscalArtifactIdentity, UUID> {
}
