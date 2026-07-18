package com.tpverp.backend.control;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlAlertHistoryRepository extends JpaRepository<ControlAlertHistory, UUID> {
    List<ControlAlertHistory> findAllByAlertIdOrderByChangedAtAsc(UUID alertId);
}
