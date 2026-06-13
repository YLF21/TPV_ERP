package com.tpverp.backend.backup;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfiguracionBackupRepository extends JpaRepository<ConfiguracionBackup, UUID> {

    Optional<ConfiguracionBackup> findByInstalacionId(UUID instalacionId);
}
