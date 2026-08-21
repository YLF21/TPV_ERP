package com.tpverp.saas.loyalty;

import com.tpverp.saas.loyalty.MemberPointsOfficialApiModels.FeedRequest;
import com.tpverp.saas.loyalty.MemberPointsOfficialApiModels.FeedResponse;
import com.tpverp.saas.loyalty.MemberPointsOfficialApiModels.ManualAdjustmentRequest;
import com.tpverp.saas.loyalty.MemberPointsOfficialApiModels.OfficialAccount;
import com.tpverp.saas.sync.SyncEventRequest;
import com.tpverp.saas.sync.SyncEventService;
import com.tpverp.saas.sync.SyncOperation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v2/loyalty/member-points")
public class MemberPointsOfficialController {
    private static final String TOKEN_HEADER = "X-TPV-Installation-Token";

    private final MemberPointsOfficialService official;
    private final SyncEventService syncEvents;

    public MemberPointsOfficialController(
            MemberPointsOfficialService official,
            SyncEventService syncEvents) {
        this.official = official;
        this.syncEvents = syncEvents;
    }

    @PostMapping("/official-feed")
    public FeedResponse feed(
            @RequestHeader(TOKEN_HEADER) String token,
            @RequestBody FeedRequest request) {
        return official.feed(request, token);
    }

    @PostMapping("/adjustments")
    public OfficialAccount adjust(
            @RequestHeader(TOKEN_HEADER) String token,
            @RequestBody ManualAdjustmentRequest request) {
        validate(request);
        official.feed(new FeedRequest(
                request.companyId(), request.storeId(), 0, 1), token);
        syncEvents.receive(syncRequest(request), token);
        return official.account(
                request.companyId(), request.storeId(), request.memberId(), token);
    }

    private static SyncEventRequest syncRequest(ManualAdjustmentRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("operationId", request.operationId());
        payload.put("memberId", request.memberId());
        payload.put("operationType", "MANUAL_ADJUSTMENT");
        payload.put("amount", request.amount());
        payload.put("sourceDocumentId", null);
        payload.put("originalDocumentId", null);
        payload.put("occurredAt", request.occurredAt());
        payload.put("localPointsDelta", 0);
        payload.put("localDebtDelta", 0);
        return new SyncEventRequest(
                request.operationId(),
                request.companyId(),
                request.storeId(),
                request.storeSequence(),
                null,
                "MEMBER_POINTS_OPERATION",
                request.operationId(),
                SyncOperation.CREAR,
                payload);
    }

    private static void validate(ManualAdjustmentRequest request) {
        if (request.companyId() == null
                || request.storeId() == null
                || request.operationId() == null
                || request.memberId() == null
                || request.occurredAt() == null) {
            throw invalid("Faltan datos obligatorios del ajuste de puntos");
        }
        if (request.storeSequence() <= 0) {
            throw invalid("storeSequence debe ser positiva");
        }
        if (request.amount() == 0) {
            throw invalid("El ajuste manual de puntos no puede ser cero");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
