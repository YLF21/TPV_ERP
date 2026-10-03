package com.tpverp.saas.marketing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DemoRequestView(
        UUID id,
        DemoProduct product,
        List<DemoProduct> products,
        String name,
        String company,
        String email,
        String phone,
        String message,
        String locale,
        String landingPath,
        String referrer,
        String utmSource,
        String utmMedium,
        String utmCampaign,
        String status,
        Instant privacyAcceptedAt,
        Instant createdAt) {

    public DemoRequestView {
        products = List.copyOf(products);
    }
}
