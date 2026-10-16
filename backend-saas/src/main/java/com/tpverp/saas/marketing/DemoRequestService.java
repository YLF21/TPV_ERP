package com.tpverp.saas.marketing;

import com.tpverp.saas.admin.AdminAuditService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoRequestService {

    static final int ADMIN_INBOX_LIMIT = 100;

    private final DemoRequestRepository requests;
    private final Clock clock;
    private final AdminAuditService audit;
    private final DemoRequestRateLimiter limiter;

    public DemoRequestService(DemoRequestRepository requests, Clock clock, AdminAuditService audit,
            @Value("${tpv.saas.marketing.ip-request-limit:120}") int ipRequestLimit) {
        this.requests = requests;
        this.clock = clock;
        this.audit = audit;
        this.limiter = new DemoRequestRateLimiter(clock, ipRequestLimit);
    }

    @Transactional
    public DemoRequestReceipt submit(CreateDemoRequest request) {
        return submit(request, null, null);
    }

    @Transactional
    public DemoRequestReceipt submit(CreateDemoRequest request, UUID idempotencyKey, String remoteAddress) {
        UUID id = UUID.randomUUID();
        // PostgreSQL timestamps keep microseconds. Persist and return exactly the same receipt.
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        String fingerprint = fingerprint(request);
        if (idempotencyKey != null) {
            var existing = requests.findSubmission(idempotencyKey);
            if (existing.isPresent()) return replay(existing.get(), fingerprint);
        }
        limiter.accept(remoteAddress, requiredText(request.email()).toLowerCase(Locale.ROOT));

        // A filled website field identifies automated submissions. Return the same neutral
        // receipt as a real request so the endpoint does not teach bots how to bypass it.
        if (text(request.website()) != null) {
            return new DemoRequestReceipt(id, receivedAt);
        }

        List<DemoProduct> selectedProducts = request.selectedProducts();
        DemoRequestView submission = new DemoRequestView(
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
                receivedAt);
        if (idempotencyKey == null) requests.save(submission);
        else if (!requests.save(submission, idempotencyKey, fingerprint)) {
            // Concurrent INSERT waits for the winner. READ COMMITTED then observes its receipt.
            return replay(requests.findSubmission(idempotencyKey).orElseThrow(() ->
                    new DemoRequestFailure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                            "No se pudo confirmar la recepcion. Reintenta con la misma clave")), fingerprint);
        }
        return new DemoRequestReceipt(id, receivedAt);
    }

    @Transactional(readOnly = true)
    public boolean available() {
        return requests.available();
    }

    @Transactional(readOnly = true)
    public List<DemoRequestView> latest() {
        return requests.findLatest(ADMIN_INBOX_LIMIT);
    }

    @Transactional(readOnly = true)
    public DemoRequestPage page(int page, int size, DemoRequestStatus status, String query) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new DemoRequestFailure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Paginacion invalida", Map.of("page", "INVALID", "size", "INVALID"), null);
        }
        String normalizedQuery = text(query);
        if (normalizedQuery != null && normalizedQuery.length() > 160) {
            throw new DemoRequestFailure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Busqueda demasiado larga", Map.of("query", "TOO_LONG"), null);
        }
        return requests.page(page, size, status, normalizedQuery);
    }

    @Transactional
    public DemoRequestView updateStatus(UUID id, DemoRequestStatus status, DemoRequestStatus expectedStatus) {
        var changed = requests.updateStatus(id, status, expectedStatus);
        if (changed.isEmpty()) {
            DemoRequestView current = requests.findById(id).orElseThrow(() ->
                    new DemoRequestFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Solicitud no encontrada"));
            // Lost success responses may safely repeat the same destination without another audit event.
            if (current.status().equals(status.name())) return current;
            throw new DemoRequestFailure(HttpStatus.CONFLICT, "STATUS_CONFLICT",
                    "La solicitud ha cambiado. Actualiza la bandeja antes de modificar su estado");
        }
        DemoRequestView updated = changed.get();
        // Only identifiers and the new state enter the admin audit, never contact content.
        audit.log("UPDATE_DEMO_REQUEST_STATUS", "MARKETING_DEMO_REQUEST", id.toString(), status.name());
        return updated;
    }

    private static DemoRequestReceipt replay(DemoRequestRepository.StoredSubmission existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new DemoRequestFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "La clave ya se utilizo con otra solicitud");
        }
        return existing.receipt();
    }

    private static String fingerprint(CreateDemoRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String[] values = {request.selectedProducts().toString(), requiredText(request.name()),
                    requiredText(request.company()), requiredText(request.email()).toLowerCase(Locale.ROOT),
                    text(request.phone()), text(request.message()), request.locale(),
                    String.valueOf(request.privacyAccepted()), text(request.landingPath()), text(request.referrer()),
                    text(request.utmSource()), text(request.utmMedium()), text(request.utmCampaign())};
            for (String value : values) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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
