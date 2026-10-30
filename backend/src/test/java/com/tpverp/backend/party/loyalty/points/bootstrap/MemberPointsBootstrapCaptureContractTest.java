package com.tpverp.backend.party.loyalty.points.bootstrap;

import static com.tpverp.backend.party.loyalty.points.bootstrap.MemberPointsBootstrapContractResolverTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.party.MemberPointsOperationRepository;
import com.tpverp.backend.party.MemberRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionCoordinator.FreezeResult;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionState;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStateRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStatus;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

class MemberPointsBootstrapCaptureContractTest {
    private final MemberPointsBootstrapContractResolverTest fixture = new MemberPointsBootstrapContractResolverTest();
    private final UUID bootstrapId = UUID.randomUUID(), snapshotId = UUID.randomUUID();
    private final MemberRepository members = mock(MemberRepository.class);
    private final MemberPointsOperationRepository operations = mock(MemberPointsOperationRepository.class);
    private final MemberPointsProjectionStateRepository states = mock(MemberPointsProjectionStateRepository.class);
    private final MemberPointsBootstrapSnapshotRepository snapshots = mock(MemberPointsBootstrapSnapshotRepository.class);
    private final MemberPointsBootstrapSnapshotAccountRepository accounts = mock(MemberPointsBootstrapSnapshotAccountRepository.class);
    private final MemberPointsBootstrapSnapshotOperationRepository snapshotOperations = mock(MemberPointsBootstrapSnapshotOperationRepository.class);
    private final MemberPointsBootstrapUploadRepository uploads = mock(MemberPointsBootstrapUploadRepository.class);
    private final Clock clock = Clock.fixed(STORED.plusSeconds(10), ZoneOffset.UTC);
    private final FreezeResult freeze = new FreezeResult(COMPANY, STORE, bootstrapId, snapshotId, STORED, 23);

    @BeforeEach
    void setUp() throws Exception {
        fixture.setUp();
        var state = mock(MemberPointsProjectionState.class);
        when(state.getStatus()).thenReturn(MemberPointsProjectionStatus.FROZEN);
        when(state.getBootstrapId()).thenReturn(bootstrapId);
        when(state.getSnapshotId()).thenReturn(snapshotId);
        when(state.getCutSequence()).thenReturn(23L);
        when(state.getLastSequence()).thenReturn(23L);
        when(states.findById(STORE)).thenReturn(Optional.of(state));
        when(states.findLockedByStoreId(STORE)).thenReturn(Optional.of(state));
        when(members.findPointsSnapshotAccounts(eq(COMPANY), any())).thenReturn(new SliceImpl<>(List.of()));
        when(operations.findBootstrapOperations(eq(COMPANY), eq(STORE), eq(23L), any()))
                .thenReturn(new SliceImpl<>(List.of(fixture.row)));
        when(snapshots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void capturaAbsorbedConContratoOriginalNoConFechaRedondeada() {
        var result = captureService().capture(freeze);
        assertThat(result.operationCount()).isEqualTo(1);
        verify(snapshotOperations).saveAll(argThat(values -> {
            var operation = values.iterator().next();
            return operation.getContractHash().equals(hash(ORIGINAL))
                    && operation.getOperationId().equals(OPERATION)
                    && operation.getSourceSequence() == 23;
        }));
    }

    @Test
    void contratoIncompatibleImpidePersistirSnapshot() {
        when(fixture.event.getStoreSequence()).thenReturn(22L);
        assertThatThrownBy(() -> captureService().capture(freeze)).isInstanceOf(IllegalStateException.class);
        verify(snapshots, never()).saveAndFlush(any());
        verifyNoInteractions(snapshotOperations, accounts);
    }

    @Test
    void selladoReplayUsaElMismoContratoExactoQueCapturaAbsorbed() {
        var snapshot = new MemberPointsBootstrapSnapshot(snapshotId, bootstrapId, COMPANY, STORE,
                STORED, 22L, 0, 0, 0, 0, "unused", STORED);
        when(snapshots.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(operations.findBootstrapOperationsRange(eq(COMPANY), eq(STORE), eq(22L), eq(23L), any()))
                .thenReturn(new SliceImpl<>(List.of(fixture.row)));
        when(accounts.findChunk(eq(snapshotId), any())).thenReturn(new SliceImpl<>(List.of()));
        when(snapshotOperations.findAbsorbedChunk(eq(snapshotId), eq(22L), any())).thenReturn(new SliceImpl<>(List.of()));
        when(snapshotOperations.findReplayChunk(eq(snapshotId), eq(22L), any()))
                .thenReturn(new SliceImpl<>(List.of(new MemberPointsBootstrapSnapshotOperation(
                        snapshotId, OPERATION, hash(ORIGINAL), 23L))));
        when(uploads.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = new MemberPointsBootstrapSealService(states, snapshots, accounts,
                snapshotOperations, operations, uploads, fixture.resolver, clock);

        var result = service.seal(COMPANY, STORE, bootstrapId, snapshotId);

        assertThat(result.getReplayCount()).isEqualTo(1);
        verify(snapshotOperations).saveAllAndFlush(argThat(values ->
                values.iterator().next().getContractHash().equals(hash(ORIGINAL))));
    }

    private MemberPointsBootstrapCaptureService captureService() {
        return new MemberPointsBootstrapCaptureService(members, operations, states, snapshots,
                accounts, snapshotOperations, fixture.resolver, clock);
    }
}
