package com.tpverp.backend.party.loyalty.sync;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboxService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes the single canonical outbox contract for return-balance recovery. */
@Service
public class MemberReturnBalanceRecoveryOutboxPublisher {

    public static final String ENTITY_TYPE = "MEMBER_RETURN_BALANCE_RECOVERY";

    private final SyncOutboxService syncOutbox;

    @Autowired
    public MemberReturnBalanceRecoveryOutboxPublisher(SyncOutboxService syncOutbox) {
        this.syncOutbox = syncOutbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SyncOutboxEvent publish(MemberReturnBalanceRecoveryCommand command) {
        Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(syncOutbox.enqueue(new SyncOutboundEventCommand(
                command.companyId(), command.storeId(), command.terminalId(),
                ENTITY_TYPE, command.operationId(), SyncOperation.CONFIRMAR,
                canonicalPayload(command))),
                "syncOutbox.enqueue debe devolver el evento");
    }

    /** Exposes the only serialization of the recovery contract for later phases. */
    public Map<String, Object> canonicalPayload(MemberReturnBalanceRecoveryCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("companyId", command.companyId().toString());
        payload.put("storeId", command.storeId().toString());
        payload.put("memberId", command.memberId().toString());
        payload.put("sourceDocumentId", command.sourceDocumentId().toString());
        payload.put("returnDocumentId", command.returnDocumentId().toString());
        payload.put("attributedAmount", command.attributedAmount());
        payload.put("claimsFingerprint", command.claimsFingerprint());
        payload.put("claims", command.claims().stream()
                .map(MemberReturnBalanceRecoveryOutboxPublisher::claimPayload)
                .toList());
        if (command.reservation() != null) {
            payload.put("reservationId",
                    command.reservation().centralReservationId().toString());
            payload.put("reservationSaleId", command.reservation().saleId());
        }
        return Collections.unmodifiableMap(payload);
    }

    private static Map<String, Object> claimPayload(
            MemberBalanceCentralGateway.RetentionClaim claim) {
        Objects.requireNonNull(claim, "claim");
        return Map.of(
                "lotId", Objects.requireNonNull(claim.lotId(), "claim.lotId").toString(),
                "sourceMovementId", Objects.requireNonNull(claim.sourceMovementId(),
                        "claim.sourceMovementId").toString(),
                "sourceDocumentId", Objects.requireNonNull(claim.sourceDocumentId(),
                        "claim.sourceDocumentId").toString(),
                "amountOriginal", Objects.requireNonNull(claim.amountOriginal(),
                        "claim.amountOriginal"),
                "amount", Objects.requireNonNull(claim.amount(), "claim.amount"));
    }
}
