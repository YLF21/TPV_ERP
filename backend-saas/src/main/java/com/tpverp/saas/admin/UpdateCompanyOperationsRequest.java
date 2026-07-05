package com.tpverp.saas.admin;

import jakarta.validation.constraints.Size;
import java.time.Instant;

public record UpdateCompanyOperationsRequest(
        @Size(max = 80) String planName,
        @Size(max = 32) String billingStatus,
        Instant renewalDate,
        @Size(max = 32) String monthlyPrice,
        @Size(max = 32) String supportStatus,
        @Size(max = 160) String contactName,
        @Size(max = 160) String contactEmail,
        @Size(max = 4000) String notes) {
}
