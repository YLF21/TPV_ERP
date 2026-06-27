package com.tpverp.backend.backup;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupExecutionRepository extends JpaRepository<BackupExecution, UUID> {

    List<BackupExecution> findByConfiguracionIdOrderByIniciadaEnDesc(UUID configuracionId);
}
