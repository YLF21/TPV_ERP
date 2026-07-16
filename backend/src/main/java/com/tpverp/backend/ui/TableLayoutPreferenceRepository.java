package com.tpverp.backend.ui;

import com.tpverp.backend.security.domain.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableLayoutPreferenceRepository
        extends JpaRepository<TableLayoutPreference, UUID> {

    List<TableLayoutPreference> findAllByUserAndAppOrderByTableKeyAsc(
            UserAccount user, String app);

    Optional<TableLayoutPreference> findByUserAndAppAndTableKey(
            UserAccount user, String app, String tableKey);
}
