package com.tpverp.backend.party.loyalty.points.bootstrap;

import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberCategoryRepository;
import com.tpverp.backend.party.MemberRepository;
import com.tpverp.backend.party.MemberSettingsRepository;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapChunkKind;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsOfficialStateChunk;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStateRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStatus;
import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsBootstrapProgressService {
    private final MemberPointsBootstrapUploadRepository uploads;
    private final MemberPointsOfficialSnapshotAccountRepository officialAccounts;
    private final MemberPointsProjectionStateRepository states;
    private final MemberRepository members;
    private final MemberSettingsRepository settings;
    private final MemberCategoryRepository categories;
    private final Clock clock;

    public MemberPointsBootstrapProgressService(
            MemberPointsBootstrapUploadRepository uploads,
            MemberPointsOfficialSnapshotAccountRepository officialAccounts,
            MemberPointsProjectionStateRepository states,
            MemberRepository members,
            MemberSettingsRepository settings,
            MemberCategoryRepository categories,
            Clock clock) {
        this.uploads = uploads;
        this.officialAccounts = officialAccounts;
        this.states = states;
        this.members = members;
        this.settings = settings;
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional
    public void markBeginSent(UUID snapshotId) {
        upload(snapshotId).markBeginSent(clock.instant());
    }

    @Transactional
    public void markChunkSent(
            UUID snapshotId,
            PointsBootstrapChunkKind kind,
            int index) {
        var upload = upload(snapshotId);
        Instant now = clock.instant();
        switch (kind) {
            case ACCOUNTS -> upload.advanceAccountChunk(index, now);
            case ABSORBED_OPERATIONS -> upload.advanceAbsorbedChunk(index, now);
            case REPLAY_OPERATIONS -> upload.advanceReplayChunk(index, now);
        }
    }

    @Transactional
    public void markSubmitted(UUID snapshotId) {
        var upload = upload(snapshotId);
        upload.markSubmitted(clock.instant());
        state(upload).waitForOfficialState();
    }

    @Transactional
    public void markRemoteStatus(UUID snapshotId, String remoteStatus) {
        var upload = upload(snapshotId);
        var state = state(upload);
        switch (remoteStatus) {
            case "CATCHING_UP" -> state.catchUp();
            case "COLLECTING", "RECONCILING" -> state.waitForOfficialState();
            case "CONFLICT", "CANCELLED" -> state.conflict();
            case "COMPLETED" -> state.waitForOfficialState();
            default -> throw new IllegalStateException(
                    "Estado remoto de bootstrap desconocido: " + remoteStatus);
        }
    }

    @Transactional
    public boolean stageOfficialChunk(
            UUID companyId,
            UUID storeId,
            UUID snapshotId,
            PointsOfficialStateChunk chunk) {
        var upload = upload(snapshotId);
        upload.requireContext(companyId, storeId);
        if (!upload.getBootstrapId().equals(chunk.bootstrapId())) {
            throw new IllegalStateException("El chunk oficial pertenece a otro bootstrap");
        }
        if (chunk.chunkIndex() != upload.getNextOfficialChunk()) {
            throw new IllegalStateException("El chunk oficial no coincide con el cursor local");
        }
        var lines = new ArrayList<String>();
        var staged = new ArrayList<MemberPointsOfficialSnapshotAccount>();
        Instant now = clock.instant();
        for (var account : chunk.accounts()) {
            long points = account.points().longValueExact();
            long pointsDebt = account.pointsDebt().longValueExact();
            if (points < 0 || pointsDebt < 0 || account.memberId() == null) {
                throw new IllegalStateException("Cuenta oficial de puntos invalida");
            }
            var value = new MemberPointsBootstrapCanonicalizer.AccountValue(
                    account.memberId(), points, pointsDebt);
            lines.add(MemberPointsBootstrapCanonicalizer.accountLine(value));
            UUID id = MemberPointsOfficialSnapshotAccount.deterministicId(
                    snapshotId, account.memberId());
            var existing = officialAccounts.findById(id);
            if (existing.isPresent()) {
                throw new IllegalStateException(
                        "El socio aparece repetido en el snapshot oficial");
            }
            staged.add(new MemberPointsOfficialSnapshotAccount(
                    snapshotId,
                    account.memberId(),
                    points,
                    pointsDebt,
                    chunk.revision(),
                    now));
        }
        String calculatedHash = MemberPointsContractCanonicalizer.sha256(
                String.join("", lines));
        if (!calculatedHash.equalsIgnoreCase(chunk.chunkHash())) {
            throw new IllegalStateException("Hash invalido en el chunk oficial de puntos");
        }
        upload.configureOfficialState(
                chunk.revision(),
                chunk.centralWatermark(),
                chunk.totalChunks(),
                now);
        officialAccounts.saveAll(staged);
        upload.advanceOfficialChunk(chunk.chunkIndex(), now);
        return upload.officialStateComplete();
    }

    @Transactional
    public void activateCentral(UUID companyId, UUID storeId, UUID snapshotId) {
        var upload = upload(snapshotId);
        upload.requireContext(companyId, storeId);
        if (!upload.officialStateComplete()) {
            throw new IllegalStateException("El snapshot oficial aun no esta completo");
        }
        var state = state(upload);
        if (state.getStatus() != MemberPointsProjectionStatus.WAITING_OFFICIAL
                && state.getStatus() != MemberPointsProjectionStatus.CATCHING_UP) {
            throw new IllegalStateException(
                    "El estado local no admite aplicar el snapshot oficial");
        }
        Instant syncedAt = clock.instant();
        var localMembers = members.findByCompanyIdOrderByCustomerFiscalNameAsc(companyId);
        localMembers.forEach(member -> member.applyOfficialPoints(0, 0, syncedAt));
        var byId = new java.util.HashMap<UUID, Member>();
        localMembers.forEach(member -> byId.put(member.getId(), member));
        for (var account : officialAccounts.findBySnapshotIdOrderByMemberId(snapshotId)) {
            var member = byId.get(account.getMemberId());
            if (member != null) {
                member.applyOfficialPoints(
                        account.getPoints(), account.getPointsDebt(), syncedAt);
            }
        }
        localMembers.forEach(member -> applyAutomaticCategory(companyId, member));
        state.activateCentral(upload.getSealSequence(), 0);
    }

    private MemberPointsBootstrapUpload upload(UUID snapshotId) {
        return uploads.findForUpdate(snapshotId)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe progreso local del bootstrap de puntos"));
    }

    private com.tpverp.backend.party.loyalty.points.MemberPointsProjectionState state(
            MemberPointsBootstrapUpload upload) {
        var state = states.findLockedByStoreId(upload.getStoreId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe estado local de proyeccion de puntos"));
        state.requireCompany(upload.getCompanyId());
        if (!upload.getBootstrapId().equals(state.getBootstrapId())
                || !upload.getSnapshotId().equals(state.getSnapshotId())) {
            throw new IllegalStateException("El progreso no coincide con el estado local");
        }
        return state;
    }

    private void applyAutomaticCategory(UUID companyId, Member member) {
        var config = settings.findById(companyId).orElse(null);
        if (config == null
                || !config.isCategoryAutoEnabled()
                || member.isAutoCategoryLocked()
                || (member.getMemberCategory() != null
                        && member.getMemberCategory().isManualOnly())) {
            return;
        }
        var selected = categories
                .findByCompanyIdAndActiveTrueOrderByMinPointsDesc(companyId).stream()
                .filter(category -> !category.isManualOnly())
                .filter(category -> member.getMemberPoints() >= category.getMinPoints())
                .findFirst();
        member.setCategory(selected.orElse(null), false);
    }
}
