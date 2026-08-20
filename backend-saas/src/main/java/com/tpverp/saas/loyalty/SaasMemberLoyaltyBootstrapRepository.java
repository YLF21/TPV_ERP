package com.tpverp.saas.loyalty;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberLoyaltyBootstrapRepository
        extends JpaRepository<SaasMemberLoyaltyBootstrap, UUID> {
}

