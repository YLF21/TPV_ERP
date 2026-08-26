package com.tpverp.backend.party.loyalty.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.party.MemberCategoryRepository;
import com.tpverp.backend.party.MemberMovementRepository;
import com.tpverp.backend.party.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberCategoryBootstrapCaptureServiceTest {

    @Test
    void reutilizaElSnapshotExistenteSinCongelarDeNuevoLaProyeccion() {
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        UUID snapshotId = UUID.randomUUID();
        UUID bootstrapId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        var coordinator = mock(MemberCategoryProjectionCoordinator.class);
        var snapshots = mock(MemberCategoryBootstrapSnapshotRepository.class);
        var snapshot = new MemberCategoryBootstrapSnapshot(
                snapshotId,
                bootstrapId,
                companyId,
                storeId,
                2,
                3,
                "categories",
                "assignments",
                "checksum",
                now);
        when(snapshots.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        var service = new MemberCategoryBootstrapCaptureService(
                coordinator,
                mock(MemberCategoryRepository.class),
                mock(MemberRepository.class),
                mock(MemberMovementRepository.class),
                snapshots,
                mock(MemberCategoryBootstrapCategoryRepository.class),
                mock(MemberCategoryBootstrapAssignmentRepository.class),
                Clock.fixed(now, ZoneOffset.UTC));

        var result = service.freezeAndCapture(
                companyId, storeId, bootstrapId, snapshotId);

        assertThat(result.snapshotId()).isEqualTo(snapshotId);
        assertThat(result.categoryCount()).isEqualTo(2);
        assertThat(result.assignmentCount()).isEqualTo(3);
        verifyNoInteractions(coordinator);
    }
}
