package com.tpverp.saas.admin;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import java.time.Instant;

public record UpdateCompanyOperationsRequest(
        @Size(max = 80) String planName,
        @Size(max = 32) String billingStatus,
        Instant renewalDate,
        @Size(max = 32) String monthlyPrice,
        @Size(max = 32) String supportStatus,
        @Size(max = 160) String contactName,
        @Email @Size(max = 160) String contactEmail,
        @Size(max = 4000) String notes,
        @Size(max = 40) String contactPhone) {
    public UpdateCompanyOperationsRequest(String planName, String billingStatus,
            Instant renewalDate, String monthlyPrice, String supportStatus,
            String contactName, String contactEmail, String notes) {
        this(planName, billingStatus, renewalDate, monthlyPrice,
                supportStatus, contactName, contactEmail, notes, null);
    }
}
