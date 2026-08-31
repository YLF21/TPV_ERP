package com.tpverp.backend.party.loyalty.central;

import java.util.UUID;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev")
@ConditionalOnExpression("'${tpv.sync.central-url:}' == ''")
public class DisabledMemberBalanceCentralGateway implements MemberBalanceCentralGateway {

    @Override
    public BootstrapResponse bootstrap(BootstrapRequest request) {
        throw unavailable();
    }

    @Override
    public Optional<MemberWalletBootstrapStatus> discoverBootstrap(
            BootstrapStoreRequest request) {
        throw unavailable();
    }

    @Override
    public void beginBootstrapSnapshot(
            UUID bootstrapId,
            BootstrapSnapshotBeginRequest request) {
        throw unavailable();
    }

    @Override
    public void uploadBootstrapChunk(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapChunkKind kind,
            int index,
            BootstrapSnapshotChunkRequest request) {
        throw unavailable();
    }

    @Override
    public void completeBootstrapSnapshot(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapSnapshotCompleteRequest request) {
        throw unavailable();
    }

    @Override
    public MemberWalletBootstrapStatus bootstrapStatus(
            UUID bootstrapId,
            BootstrapStoreRequest request) {
        throw unavailable();
    }

    @Override
    public ReservationResponse reserve(ReserveRequest request) {
        throw unavailable();
    }

    @Override
    public ReservationResponse heartbeat(UUID reservationId, ReservationOwnerRequest request) {
        throw unavailable();
    }

    @Override
    public ReservationResponse release(UUID reservationId, ReservationOwnerRequest request) {
        throw unavailable();
    }

    @Override
    public ReservationResponse prepare(UUID reservationId, PrepareRequest request) {
        throw unavailable();
    }

    @Override
    public ReservationResponse finalizePrepared(UUID reservationId, PreparedOwnerRequest request) {
        throw unavailable();
    }

    @Override
    public ReservationResponse abortPrepared(UUID reservationId, PreparedOwnerRequest request) {
        throw unavailable();
    }

    private MemberBalanceCentralException unavailable() {
        return new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El servicio central de saldo del miembro no esta configurado");
    }
}
