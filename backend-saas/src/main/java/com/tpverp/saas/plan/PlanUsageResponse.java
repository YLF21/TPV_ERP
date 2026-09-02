package com.tpverp.saas.plan;

import java.util.Map;
import java.util.UUID;

public record PlanUsageResponse(
        UUID companyId,
        String planName,
        Map<PlanResource, Long> usage,
        Map<PlanResource, Long> limits) {
}
