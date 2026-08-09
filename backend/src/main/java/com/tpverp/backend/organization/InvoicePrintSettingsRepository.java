package com.tpverp.backend.organization;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoicePrintSettingsRepository extends JpaRepository<InvoicePrintSettings, UUID> {
}
