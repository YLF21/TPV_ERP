package com.tpverp.saas.marketing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoRequestService {

    static final int ADMIN_INBOX_LIMIT = 100;

    private final DemoRequestRepository requests;
    private final Clock clock;

    public DemoRequestService(DemoRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional
    public DemoRequestReceipt submit(CreateDemoRequest request) {
        UUID id = UUID.randomUUID();
        Instant receivedAt = clock.instant();

        // A filled website field identifies automated submissions. Return the same neutral
        // receipt as a real request so the endpoint does not teach bots how to bypass it.
        if (text(request.website()) != null) {
            return new DemoRequestReceipt(id, receivedAt);
        }

        List<DemoProduct> selectedProducts = request.selectedProducts();
        requests.save(new DemoRequestView(
                id,
                selectedProducts.getFirst(),
                selectedProducts,
                requiredText(request.name()),
                requiredText(request.company()),
                requiredText(request.email()).toLowerCase(Locale.ROOT),
                text(request.phone()),
                text(request.message()),
                request.locale(),
                text(request.landingPath()),
                text(request.referrer()),
                text(request.utmSource()),
                text(request.utmMedium()),
                text(request.utmCampaign()),
                "NEW",
                receivedAt,
                receivedAt));
        return new DemoRequestReceipt(id, receivedAt);
    }

    @Transactional(readOnly = true)
    public List<DemoRequestView> latest() {
        return requests.findLatest(ADMIN_INBOX_LIMIT);
    }

    private static String requiredText(String value) {
        return value.trim();
    }

    private static String text(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
