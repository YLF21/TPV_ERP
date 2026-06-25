package com.tpverp.backend.cash;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashStoreConfigRepository extends JpaRepository<CashStoreConfig, UUID> {
}
