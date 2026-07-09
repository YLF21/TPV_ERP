package com.tpverp.backend.promotion;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionalCouponAttemptRepository extends JpaRepository<PromotionalCouponAttempt, UUID> {
}
