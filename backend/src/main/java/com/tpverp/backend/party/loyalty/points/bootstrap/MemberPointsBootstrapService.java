package com.tpverp.backend.party.loyalty.points.bootstrap;

import com.tpverp.backend.party.PartyContext;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionCoordinator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MemberPointsBootstrapService {
    private final PartyContext context;
    private final MemberPointsProjectionCoordinator coordinator;
    private final MemberPointsBootstrapCaptureService captureService;

    public MemberPointsBootstrapService(
            PartyContext context,
            MemberPointsProjectionCoordinator coordinator,
            MemberPointsBootstrapCaptureService captureService) {
        this.context = context;
        this.coordinator = coordinator;
        this.captureService = captureService;
    }

    public MemberPointsBootstrapCaptureService.CaptureResult freezeAndCapture(
            UUID bootstrapId, UUID snapshotId, Instant cutoffAt) {
        var freeze = coordinator.freeze(
                context.currentCompany().getId(),
                context.currentStore().getId(),
                bootstrapId,
                snapshotId,
                cutoffAt);
        return captureService.capture(freeze);
    }
}
