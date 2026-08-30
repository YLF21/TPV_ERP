package com.tpverp.backend.party.loyalty.sync;

import com.tpverp.backend.party.MemberBalanceLot;
import com.tpverp.backend.party.MemberBalanceLotType;
import com.tpverp.backend.party.PartyContext;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberWalletSyncPublisher {

    private static final String ENTITY_TYPE = "MEMBER_WALLET_LOT";
    private static final ApplicationEventPublisher NO_EVENTS = event -> { };

    private final SyncOutboxService syncOutbox;
    private final PartyContext context;
    private final ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    public MemberWalletSyncPublisher(
            SyncOutboxService syncOutbox,
            PartyContext context,
            ApplicationEventPublisher events) {
        this.syncOutbox = syncOutbox;
        this.context = context;
        this.events = events;
    }

    /** Compatibilidad con construccion directa desde servicios legacy. */
    public MemberWalletSyncPublisher(SyncOutboxService syncOutbox, PartyContext context) {
        this(syncOutbox, context, NO_EVENTS);
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
        payload.put("createdAt", lot.getCreatedAt().toString());
        payload.put("expiresAt", lot.getExpiresAt() == null ? null : lot.getExpiresAt().toString());
        payload.put("sourceMovementId", sourceMovement.getId());
        payload.put("documentId", lot.getDocumentId());

        var event = syncOutbox.enqueue(new SyncOutboundEventCommand(
                context.currentCompany().getId(),
                context.currentStore().getId(),
                null,
                ENTITY_TYPE,
                lot.getId(),
                SyncOperation.CREAR,
                payload));
        if (event == null) {
            throw new IllegalStateException("syncOutbox.enqueue debe devolver el evento");
        }
        var queued = event;
        if (events != NO_EVENTS) {
            if (lot.getBalanceType() == MemberBalanceLotType.RETURN_CREDIT) {
                events.publishEvent(new MemberReturnCreditSyncRequested(queued.getEventId()));
            } else {
                events.publishEvent(new MemberWalletLotSyncRequested(queued.getEventId()));
            }
        }
    }
}
