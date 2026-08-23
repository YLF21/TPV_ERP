package com.tpverp.backend.verifactu;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalAlarmRepository extends JpaRepository<FiscalAlarm, UUID> {
}
