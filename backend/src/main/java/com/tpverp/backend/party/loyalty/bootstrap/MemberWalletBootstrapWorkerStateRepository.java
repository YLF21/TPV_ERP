package com.tpverp.backend.party.loyalty.bootstrap;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberWalletBootstrapWorkerStateRepository
        extends JpaRepository<MemberWalletBootstrapWorkerState, UUID> {
}
