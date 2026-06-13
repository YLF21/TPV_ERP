package com.tpverp.backend.backup;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EjecucionBackupRepository extends JpaRepository<EjecucionBackup, UUID> {

    List<EjecucionBackup> findByConfiguracionIdOrderByIniciadaEnDesc(UUID configuracionId);
}
