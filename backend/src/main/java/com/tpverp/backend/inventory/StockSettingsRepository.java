package com.tpverp.backend.inventory;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockSettingsRepository extends JpaRepository<StockSettings, UUID> {
}
