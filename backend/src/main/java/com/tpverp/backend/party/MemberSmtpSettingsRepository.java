package com.tpverp.backend.party;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberSmtpSettingsRepository extends JpaRepository<MemberSmtpSettings, UUID> {
}
