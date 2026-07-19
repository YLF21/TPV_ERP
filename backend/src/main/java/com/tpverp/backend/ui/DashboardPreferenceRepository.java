package com.tpverp.backend.ui;

import com.tpverp.backend.security.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DashboardPreferenceRepository extends JpaRepository<DashboardPreference, UUID> {

    Optional<DashboardPreference> findByUser(UserAccount user);
}
