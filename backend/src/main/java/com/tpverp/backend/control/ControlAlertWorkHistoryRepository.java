package com.tpverp.backend.control;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlAlertWorkHistoryRepository
        extends JpaRepository<ControlAlertWorkHistory, UUID> {

    List<ControlAlertWorkHistory> findAllByAlertIdOrderByChangedAtAsc(UUID alertId);
}
