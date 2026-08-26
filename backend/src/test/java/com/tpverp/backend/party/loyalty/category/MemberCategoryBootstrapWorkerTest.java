package com.tpverp.backend.party.loyalty.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class MemberCategoryBootstrapWorkerTest {

    @Test
    void continuaConLaInstanciaVersionadaDevueltaPorElRepositorio() {
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        UUID bootstrapId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        var context = new MemberBalanceCentralContextResolver.BootstrapContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var contexts = mock(MemberBalanceCentralContextResolver.class);
        var capture = mock(MemberCategoryBootstrapCaptureService.class);
        var snapshots = mock(MemberCategoryBootstrapSnapshotRepository.class);
        var uploads = mock(MemberCategoryBootstrapUploadRepository.class);
        var gateway = mock(MemberCategoryBootstrapGateway.class);
        var original = MemberCategoryBootstrapUpload.pending(
                context.companyId(), context.storeId(), snapshotId, now);
        var merged = MemberCategoryBootstrapUpload.pending(
                context.companyId(), context.storeId(), snapshotId, now);
        merged.start(bootstrapId, now);
        var snapshot = new MemberCategoryBootstrapSnapshot(
                snapshotId,
                bootstrapId,
                context.localCompanyId(),
                context.localStoreId(),
                0,
                0,
                "categories",
                "assignments",
                "checksum",
                now);
        var central = new MemberCategoryBootstrapGateway.BootstrapStatus(
                bootstrapId, "COLLECTING", null, null, null);

        when(contexts.resolveBootstrapContexts()).thenReturn(List.of(context));
        when(gateway.discover(context.companyId(), context.storeId())).thenReturn(central);
        when(capture.freezeAndCapture(
                        context.localCompanyId(),
                        context.localStoreId(),
                        bootstrapId,
                        UUID.nameUUIDFromBytes((bootstrapId + "|" + context.localStoreId()).getBytes())))
                .thenReturn(MemberCategoryBootstrapCaptureService.CaptureResult.from(snapshot));
        when(snapshots.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(uploads.findBySnapshotId(snapshotId)).thenReturn(Optional.of(original));
        when(uploads.save(any(MemberCategoryBootstrapUpload.class)))
                .thenReturn(merged, merged, merged);
        when(gateway.complete(
                        bootstrapId,
                        snapshotId,
                        context.companyId(),
                        context.storeId(),
                        "checksum"))
                .thenReturn(central);

        var worker = new MemberCategoryBootstrapWorker(
                contexts,
                capture,
                snapshots,
                uploads,
                gateway,
                mock(MemberCategoryProjectionCoordinator.class),
                mock(MemberCategoryOfficialSnapshotApplicationService.class),
                mock(JdbcTemplate.class),
                Clock.fixed(now, ZoneOffset.UTC));

        worker.runOnce();

        var saved = ArgumentCaptor.forClass(MemberCategoryBootstrapUpload.class);
        verify(uploads, times(3)).save(saved.capture());
        assertThat(saved.getAllValues().get(1)).isSameAs(merged);
        assertThat(saved.getAllValues().get(2)).isSameAs(merged);
    }
}
