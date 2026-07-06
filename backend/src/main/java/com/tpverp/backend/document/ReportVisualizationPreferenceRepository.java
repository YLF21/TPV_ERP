package com.tpverp.backend.document;

import com.tpverp.backend.security.domain.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportVisualizationPreferenceRepository
        extends JpaRepository<ReportVisualizationPreference, UUID> {

    List<ReportVisualizationPreference> findByUserAndApp(UserAccount user, String app);

    Optional<ReportVisualizationPreference> findByUserAndAppAndReportKey(
            UserAccount user, String app, String reportKey);
}
