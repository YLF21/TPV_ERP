package com.tpverp.backend.control;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves server-validated authorizers without inferring them from the employee or time proximity. */
@Service
public class ControlAlertAuthorizationEvidence {

    private final ControlAlertReadRepository read;

    public ControlAlertAuthorizationEvidence(ControlAlertReadRepository read) {
        this.read = read;
    }

    // The event is append-only. Enrich the response from persisted audit evidence,
    // after checkout has saved its document links, without mutating control_evento.
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, Object>> resolve(List<ControlEvent> candidates) {
        var result = new HashMap<UUID, Map<String, Object>>();
        var byStore = candidates.stream().filter(ControlAlertAuthorizationEvidence::needsEvidence)
                .collect(java.util.stream.Collectors.groupingBy(ControlEvent::getStoreId));
        for (var entry : byStore.entrySet()) {
            var documents = entry.getValue().stream().map(ControlEvent::getDocumentId).distinct().toList();
            var rows = read.authorizationEvidence(entry.getKey(), documents);
            for (var event : entry.getValue()) {
                var matching = rows.stream().filter(row -> event.getDocumentId().equals(row.getDocumentId())
                        && operation(event.getType()).equals(row.getOperationCode())
                        && row.getAuthorizedAt() != null
                        && !row.getAuthorizedAt().isAfter(event.getOccurredAt())).toList();
                if (matching.isEmpty()) continue;
                var identities = matching.stream().map(ControlAlertAuthorizationEvidence::identity).toList();
                // Incomplete or conflicting evidence cannot establish a single authorizer.
                if (identities.contains(null) || identities.stream().distinct().count() != 1) continue;
                var identity = identities.get(0);
                var data = new LinkedHashMap<String, Object>();
                data.put("authorizerId", identity.id().toString());
                data.put("authorizerName", identity.name());
                data.put("delegated", identity.delegated());
                result.put(event.getId(), Map.copyOf(data));
            }
        }
        return result;
    }

    private record Identity(UUID id, String name, boolean delegated) { }

    private static Identity identity(ControlAlertReadRepository.AuthorizationEvidence row) {
        if (row.getAuthorizerId() == null || row.getAuthorizerName() == null
                || row.getAuthorizerName().isBlank()
                || !("true".equals(row.getDelegated()) || "false".equals(row.getDelegated()))) return null;
        try {
            return new Identity(UUID.fromString(row.getAuthorizerId()), row.getAuthorizerName(),
                    Boolean.parseBoolean(row.getDelegated()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static boolean needsEvidence(ControlEvent event) {
        return event.getDocumentId() != null && operation(event.getType()) != null
                && !event.getData().containsKey("authorizerId")
                && !event.getData().containsKey("authorizerName");
    }

    private static String operation(ControlAlertType type) {
        return switch (type) {
            case PRODUCT_DISCOUNT_APPLIED, MANUAL_DISCOUNT_OVER_PERCENT -> "APPLY_SALE_DISCOUNT";
            case MANUAL_PRICE_CHANGED, MANUAL_PRICE_CHANGE_OVER_PERCENT -> "TEMPORARY_PRICE_CHANGE";
            case MANUAL_NEGATIVE_QUANTITY -> "MANUAL_RETURN_WITHOUT_TICKET";
            default -> null;
        };
    }
}
