package com.tpverp.backend.backup;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupSettingsRepository extends JpaRepository<BackupSettings, UUID> {

    Optional<BackupSettings> findByInstalacionId(UUID instalacionId);
}
