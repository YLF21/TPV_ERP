package com.tpverp.backend.organization;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreDocumentPrintSettingsRepository
        extends JpaRepository<StoreDocumentPrintSettings, UUID> {
}
