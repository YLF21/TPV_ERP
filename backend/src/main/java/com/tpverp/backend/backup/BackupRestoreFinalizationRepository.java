package com.tpverp.backend.backup;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupRestoreFinalizationRepository extends JpaRepository<BackupRestoreFinalization, UUID> { }
