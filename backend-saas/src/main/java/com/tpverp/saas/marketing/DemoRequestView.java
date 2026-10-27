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
        Instant createdAt,
        String assigneeUsername,
        String nextAction,
        Instant nextActionAt,
        long followUpVersion) {

    // Public submission and existing callers start with no internal follow-up.
    public DemoRequestView(UUID id, DemoProduct product, List<DemoProduct> products,
            String name, String company, String email, String phone, String message,
            String locale, String landingPath, String referrer, String utmSource,
            String utmMedium, String utmCampaign, String status,
            Instant privacyAcceptedAt, Instant createdAt) {
        this(id, product, products, name, company, email, phone, message, locale,
                landingPath, referrer, utmSource, utmMedium, utmCampaign, status,
                privacyAcceptedAt, createdAt, null, null, null, 0);
    }

    public DemoRequestView {
        products = List.copyOf(products);
    }
}
