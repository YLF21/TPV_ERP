package com.tpverp.backend.party.loyalty.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver.BootstrapContext;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberWalletBootstrapWorkerTest {

    @Test
    void continuaConLaInstanciaVersionadaDevueltaPorElRepositorio() {
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        BootstrapContext context = new BootstrapContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        MemberBalanceCentralContextResolver contexts =
                mock(MemberBalanceCentralContextResolver.class);
        MemberBalanceCentralGateway gateway = mock(MemberBalanceCentralGateway.class);
        MemberWalletBootstrapWorkerStateRepository states =
                mock(MemberWalletBootstrapWorkerStateRepository.class);
        MemberWalletBootstrapSnapshotRepository snapshots =
                mock(MemberWalletBootstrapSnapshotRepository.class);
        MemberWalletBootstrapWorkerState merged =
                new MemberWalletBootstrapWorkerState(context, now);

        when(contexts.resolveBootstrapContexts()).thenReturn(List.of(context));
        when(states.findById(context.localStoreId())).thenReturn(Optional.empty());
        when(states.save(any(MemberWalletBootstrapWorkerState.class))).thenReturn(merged);
        when(snapshots.findFirstByStoreIdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(gateway.discoverBootstrap(any())).thenReturn(Optional.empty());

        MemberWalletBootstrapWorker worker = new MemberWalletBootstrapWorker(
                contexts,
                gateway,
                mock(MemberWalletBootstrapCaptureService.class),
                snapshots,
                mock(MemberWalletBootstrapSnapshotAccountRepository.class),
                mock(MemberWalletBootstrapSnapshotLotRepository.class),
                states,
                Clock.fixed(now, ZoneOffset.UTC));

        worker.runOnce();

        ArgumentCaptor<MemberWalletBootstrapWorkerState> saved =
                ArgumentCaptor.forClass(MemberWalletBootstrapWorkerState.class);
        verify(states, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1)).isSameAs(merged);
    }
}
