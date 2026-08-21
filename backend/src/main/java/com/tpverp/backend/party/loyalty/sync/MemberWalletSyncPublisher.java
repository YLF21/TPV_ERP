package com.tpverp.backend.party.loyalty.sync;

import com.fasterxml.jackson.databind.node.NullNode;
import com.tpverp.backend.party.MemberBalanceLot;
import com.tpverp.backend.party.PartyContext;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberWalletSyncPublisher {

    private static final String ENTITY_TYPE = "MEMBER_WALLET_LOT";

    private final SyncOutboxService syncOutbox;
    private final PartyContext context;

    public MemberWalletSyncPublisher(
            SyncOutboxService syncOutbox,
            PartyContext context) {
        this.syncOutbox = syncOutbox;
        this.context = context;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCreated(MemberBalanceLot lot) {
        Objects.requireNonNull(lot, "lot");
        var sourceMovement = Objects.requireNonNull(
                lot.getSourceMovement(), "sourceMovement");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", 2);
        payload.put("memberId", lot.getMember().getId());
        payload.put("balanceType", lot.getBalanceType().name());
        payload.put("amount", lot.getAmountOriginal());
        payload.put("createdAt", lot.getCreatedAt());
        payload.put("expiresAt", nullable(lot.getExpiresAt()));
        payload.put("sourceMovementId", sourceMovement.getId());
        payload.put("documentId", nullable(lot.getDocumentId()));

        syncOutbox.enqueue(new SyncOutboundEventCommand(
                context.currentCompany().getId(),
                context.currentStore().getId(),
                null,
                ENTITY_TYPE,
                lot.getId(),
                SyncOperation.CREAR,
                payload));
    }

    private static Object nullable(Object value) {
        return value == null ? NullNode.getInstance() : value;
    }
}
