package com.tpverp.backend.sync;

import com.tpverp.backend.party.MemberLoyaltyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncInboxService {

    private static final String MEMBER_OFFICIAL_STATE = "MEMBER_OFFICIAL_STATE";

    private final SyncInboxEventRepository repository;
    private final MemberLoyaltyService members;
    private final Clock clock;

    public SyncInboxService(
            SyncInboxEventRepository repository,
            MemberLoyaltyService members,
            Clock clock) {
        this.repository = repository;
        this.members = members;
        this.clock = clock;
    }

    @Transactional
    public SyncInboxReceipt receive(SyncInboundEventRequest request) {
        var existing = repository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            return new SyncInboxReceipt(request.eventId(), SyncInboxResult.DUPLICADO);
        }
        var event = new SyncInboxEvent(
                request.eventId(), request.companyId(), request.storeId(), clock.instant());
        var result = SyncInboxResult.OK;
        if (MEMBER_OFFICIAL_STATE.equals(request.entityType())) {
            try {
                applyMemberOfficialState(request);
                event.markProcessed(SyncInboxResult.OK, clock.instant(), null);
            } catch (RuntimeException exception) {
                result = SyncInboxResult.ERROR;
                event.markProcessed(SyncInboxResult.ERROR, clock.instant(), errorMessage(exception));
            }
        }
        repository.save(event);
        return new SyncInboxReceipt(request.eventId(), result);
    }

    private void applyMemberOfficialState(SyncInboundEventRequest request) {
        members.applyOfficialState(new MemberLoyaltyService.OfficialMemberStateCommand(
                request.eventId(),
                request.entityId(),
                decimal(request.payload(), "balance"),
                number(request.payload(), "points").longValue(),
                uuid(request.payload().get("categoryId")),
                instant(request.payload(), "syncedAt")));
    }
    // Converts the generic SaaS sync payload into the member-domain command.

    private static BigDecimal decimal(Map<String, Object> payload, String key) {
        var value = payload.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static Number number(Map<String, Object> payload, String key) {
        var value = payload.get(key);
        if (value instanceof Number number) {
            return number;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Instant instant(Map<String, Object> payload, String key) {
        return Instant.parse(String.valueOf(payload.get(key)));
    }

    private static UUID uuid(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private static String errorMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
