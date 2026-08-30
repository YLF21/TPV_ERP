package com.tpverp.backend.party.loyalty.sync;

import java.util.UUID;
import java.util.Objects;

/** Wake específico de un evento MEMBER_WALLET_LOT ya persistido en el outbox. */
public record MemberWalletLotSyncRequested(UUID eventId) {
    public MemberWalletLotSyncRequested {
        Objects.requireNonNull(eventId, "eventId");
    }
}
