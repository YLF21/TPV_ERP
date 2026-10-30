package com.tpverp.backend.party.loyalty.points.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.tpverp.backend.party.MemberPointsOperationRepository.MemberPointsBootstrapOperationProjection;
import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import com.tpverp.backend.sync.SyncOutboxEventRepository;
import com.tpverp.backend.sync.SyncOutboxEventRepository.PointsContractOriginal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reconstructs the immutable wire contract without changing the stored operation. */
@Service
public class MemberPointsBootstrapContractResolver {
    private static final ObjectReader JSON = new ObjectMapper().readerFor(JsonNode.class)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> FIELDS = Set.of("schemaVersion", "operationId", "memberId",
            "operationType", "amount", "sourceDocumentId", "originalDocumentId", "occurredAt",
            "localPointsDelta", "localDebtDelta");
    private final SyncOutboxEventRepository outbox;

    public MemberPointsBootstrapContractResolver(SyncOutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    public Map<UUID, String> resolve(UUID companyId, UUID storeId,
            List<MemberPointsBootstrapOperationProjection> rows) {
        if (rows.isEmpty()) return Map.of();
        var ids = rows.stream().map(MemberPointsBootstrapOperationProjection::getOperationId).toList();
        Map<UUID, List<Original>> originals = new HashMap<>();
        ids.forEach(id -> originals.put(id, new ArrayList<>()));
        for (var event : outbox.findPointsContractOriginals(ids, ids.stream().map(UUID::toString).toList())) {
            JsonNode payload;
            try {
                payload = JSON.readValue(event.getPayload());
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw invalid(event.getEntityId(), "JSON original ilegible");
            }
            var original = new Original(event, payload);
            if (originals.containsKey(event.getEntityId())) originals.get(event.getEntityId()).add(original);
            UUID declared;
            try {
                declared = optionalUuid(payload == null ? null : payload.get("operationId"));
            } catch (IllegalArgumentException exception) {
                throw invalid(event.getEntityId(), "operationId original invalido");
            }
            if (originals.containsKey(declared) && !Objects.equals(declared, event.getEntityId())) {
                originals.get(declared).add(original);
            }
        }
        Map<UUID, String> result = new HashMap<>();
        for (var row : rows) {
            var candidates = originals.get(row.getOperationId());
            if (candidates.size() > 1) throw invalid(row.getOperationId(), "Hay varios eventos originales candidatos");
            // Historical rows without an outbox retain the pre-existing database contract.
            // An existing but incompatible original must never fall back to that contract.
            Instant occurredAt = candidates.isEmpty() ? row.getOccurredAt()
                    : validateOriginal(companyId, storeId, row, candidates.getFirst());
            result.put(row.getOperationId(), MemberPointsContractCanonicalizer.contractHash(
                    row.getOperationId(), row.getMemberId(), row.getOperationType(), row.getAmount(),
                    row.getSourceDocumentId(), row.getOriginalDocumentId(), occurredAt,
                    row.getLocalPointsDelta(), row.getLocalDebtDelta()));
        }
        return Map.copyOf(result);
    }

    private Instant validateOriginal(UUID companyId, UUID storeId,
            MemberPointsBootstrapOperationProjection row, Original original) {
        var event = original.event();
        var payload = original.payload();
        try {
            require(event.getEventId() != null && Objects.equals(companyId, event.getCompanyId())
                    && Objects.equals(storeId, event.getStoreId())
                    && Objects.equals(row.getStoreSequence(), event.getStoreSequence())
                    && "MEMBER_POINTS_OPERATION".equals(event.getEntityType())
                    && "CREAR".equals(event.getOperation())
                    && Objects.equals(row.getOperationId(), event.getEntityId()), "Procedencia original incompatible");
            require(payload != null && payload.isObject(), "Payload original no es un objeto");
            payload.fieldNames().forEachRemaining(field -> require(FIELDS.contains(field), "Campo original no permitido"));
            require(integer(payload.get("schemaVersion")) == 1
                    && Objects.equals(row.getOperationId(), uuid(payload.get("operationId")))
                    && Objects.equals(row.getMemberId(), uuid(payload.get("memberId")))
                    && row.getOperationType().name().equals(text(payload.get("operationType")))
                    && row.getAmount() == integer(payload.get("amount"))
                    && Objects.equals(row.getSourceDocumentId(), optionalUuid(payload.get("sourceDocumentId")))
                    && Objects.equals(row.getOriginalDocumentId(), optionalUuid(payload.get("originalDocumentId")))
                    && row.getLocalPointsDelta() == integer(payload.get("localPointsDelta"))
                    && row.getLocalDebtDelta() == integer(payload.get("localDebtDelta")), "Contenido original incompatible");
            Instant occurredAt = instant(payload.get("occurredAt"));
            require(Objects.equals(row.getOccurredAt(), postgresMicroseconds(occurredAt))
                            || provesLegacyDoubleTimestamp(row, payload.get("occurredAt")),
                    "Fecha original no coincide con la operacion a precision PostgreSQL");
            return occurredAt;
        } catch (RuntimeException exception) {
            throw invalid(row.getOperationId(), exception.getMessage());
        }
    }

    private static boolean provesLegacyDoubleTimestamp(
            MemberPointsBootstrapOperationProjection row, JsonNode wireTimestamp) {
        if (wireTimestamp == null || !wireTimestamp.isNumber() || row.getOccurredAt() == null
                || row.getOccurredAt().getNano() % 1000 != 0 || row.getPayloadHash() == null) {
            return false;
        }
        // The old JSON Map could round an Instant to Double before sending it. This is
        // not a timestamp tolerance: recover the unique original nanosecond proven by
        // the immutable local fingerprint, then reproduce that exact Double conversion.
        Instant recovered = null;
        for (int offset = -500; offset < 500; offset++) {
            Instant candidate = row.getOccurredAt().plusNanos(offset);
            if (localFingerprint(row, candidate).equals(row.getPayloadHash())) {
                if (recovered != null) return false;
                recovered = candidate;
            }
        }
        if (recovered == null) return false;
        BigDecimal epoch = BigDecimal.valueOf(recovered.getEpochSecond())
                .add(BigDecimal.valueOf(recovered.getNano(), 9));
        return BigDecimal.valueOf(epoch.doubleValue()).compareTo(wireTimestamp.decimalValue()) == 0;
    }

    private static String localFingerprint(MemberPointsBootstrapOperationProjection row, Instant occurredAt) {
        // Historical v1 fingerprint persisted by MemberPointsOperation; it differs from
        // the central pipe-delimited contract and deliberately has no trailing newline.
        String canonical = String.join("\n", "schemaVersion=1",
                "operationId=" + row.getOperationId(), "memberId=" + row.getMemberId(),
                "operationType=" + row.getOperationType().name(), "amount=" + row.getAmount(),
                "sourceDocumentId=" + (row.getSourceDocumentId() == null ? "-" : row.getSourceDocumentId()),
                "originalDocumentId=" + (row.getOriginalDocumentId() == null ? "-" : row.getOriginalDocumentId()),
                "occurredAt=" + occurredAt, "localPointsDelta=" + row.getLocalPointsDelta(),
                "localDebtDelta=" + row.getLocalDebtDelta());
        return MemberPointsContractCanonicalizer.sha256(canonical);
    }

    private static Instant instant(JsonNode node) {
        if (node != null && node.isTextual()) return Instant.parse(node.textValue());
        require(node != null && node.isNumber(), "Fecha original no es ISO ni segundos epoch");
        BigDecimal seconds = node.decimalValue();
        require(seconds.compareTo(BigDecimal.valueOf(Instant.MIN.getEpochSecond())) >= 0
                && seconds.compareTo(BigDecimal.valueOf(Instant.MAX.getEpochSecond()).add(BigDecimal.ONE)) < 0
                && seconds.stripTrailingZeros().scale() <= 9, "Fecha original fuera de rango o precision");
        long whole = seconds.setScale(0, RoundingMode.DOWN).longValueExact();
        long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValueExact();
        return Instant.ofEpochSecond(whole, nanos);
    }

    private static Instant postgresMicroseconds(Instant value) {
        var truncated = value.truncatedTo(ChronoUnit.MICROS);
        return value.getNano() % 1000 >= 500 ? truncated.plusNanos(1000) : truncated;
    }

    private static long integer(JsonNode node) {
        require(node != null && (node.isNumber() || node.isTextual()), "Entero original obligatorio");
        String value = node.asText();
        require(value.matches("-?(0|[1-9][0-9]*)"), "Entero original invalido");
        return new BigDecimal(value).longValueExact();
    }

    private static String text(JsonNode node) {
        require(node != null && node.isTextual(), "Texto original obligatorio");
        return node.textValue();
    }

    private static UUID uuid(JsonNode node) { return UUID.fromString(text(node)); }
    private static UUID optionalUuid(JsonNode node) { return node == null || node.isNull() ? null : uuid(node); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static IllegalStateException invalid(UUID operationId, String reason) {
        return new IllegalStateException("No se puede reconstruir el contrato de puntos " + operationId + ": " + reason);
    }
    private record Original(PointsContractOriginal event, JsonNode payload) {}
}
