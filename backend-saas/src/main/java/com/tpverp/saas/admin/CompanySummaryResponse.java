package com.tpverp.saas.admin;

import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.TaxpayerType;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

public record CompanySummaryResponse(UUID companyId, String companyName, String taxId,
        TaxpayerType taxpayerType, CommercialProfile commercialProfile,
        Map<String, String> companyAddress, Instant createdAt,
        String contactName, String contactEmail, String contactPhone,
        String supportStatus, String notes, List<CompanyOwner> owners) { }
