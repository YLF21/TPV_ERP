package com.tpverp.backend.party.loyalty.points;

import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberCategoryRepository;
import com.tpverp.backend.party.MemberRepository;
import com.tpverp.backend.party.MemberSettingsRepository;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.OfficialPointsFeedResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsOfficialProjectionService {
    private static final int APPLY_BATCH_SIZE = 200;

    private final MemberPointsProjectionStateRepository states;
    private final MemberPointsOfficialInboxRepository inbox;
    private final MemberRepository members;
    private final MemberSettingsRepository settings;
    private final MemberCategoryRepository categories;
    private final Clock clock;

    public MemberPointsOfficialProjectionService(
            MemberPointsProjectionStateRepository states,
            MemberPointsOfficialInboxRepository inbox,
            MemberRepository members,
            MemberSettingsRepository settings,
            MemberCategoryRepository categories,
            Clock clock) {
        this.states = states;
        this.inbox = inbox;
        this.members = members;
        this.settings = settings;
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional
    public void accept(
            UUID companyId,
            UUID storeId,
            OfficialPointsFeedResponse response) {
        var state = states.findLockedByStoreId(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe estado local de proyeccion de puntos"));
        state.requireCompany(companyId);
        if (state.getStatus() != MemberPointsProjectionStatus.CENTRAL_ACTIVE) {
            throw new IllegalStateException(
                    "La tienda no tiene activa la autoridad central de puntos");
        }
        if (response.requestedAfterRevision() != state.getOfficialRevision()) {
            throw new IllegalStateException(
                    "El feed oficial parte de una revision local obsoleta");
        }
        if (response.nextRevision() < response.requestedAfterRevision()) {
            throw new IllegalStateException("El feed oficial hace retroceder la revision");
        }

        long previousRevision = response.requestedAfterRevision();
        Instant receivedAt = clock.instant();
        for (var account : response.accounts()) {
            if (account.memberId() == null
                    || account.points() == null
                    || account.pointsDebt() == null
                    || account.syncedAt() == null) {
                throw new IllegalStateException("El feed oficial contiene una cuenta incompleta");
            }
            long points = account.points().longValueExact();
            long pointsDebt = account.pointsDebt().longValueExact();
            if (points < 0 || pointsDebt < 0
                    || account.officialRevision() <= previousRevision) {
                throw new IllegalStateException("El feed oficial no esta ordenado o es invalido");
            }
            previousRevision = account.officialRevision();
            UUID inboxId = MemberPointsOfficialInbox.deterministicId(
                    storeId, account.memberId());
            var item = inbox.findById(inboxId)
                    .orElseGet(() -> new MemberPointsOfficialInbox(
                            companyId,
                            storeId,
                            account.memberId(),
                            points,
                            pointsDebt,
                            account.officialRevision(),
                            account.syncedAt(),
                            receivedAt));
            item.requireContext(companyId, storeId);
            item.replace(
                    points,
                    pointsDebt,
                    account.officialRevision(),
                    account.syncedAt(),
                    receivedAt);
            inbox.save(item);
        }
        if (!response.accounts().isEmpty()
                && previousRevision != response.nextRevision()) {
            throw new IllegalStateException(
                    "La revision final no coincide con la ultima cuenta del feed");
        }
        if (response.accounts().isEmpty()
                && response.nextRevision() != response.requestedAfterRevision()) {
            throw new IllegalStateException(
                    "Un feed vacio no puede avanzar la revision oficial");
        }
        state.advanceOfficialRevision(response.nextRevision());
    }

    @Transactional
    public int applyPending(UUID companyId, UUID storeId) {
        var state = states.findLockedByStoreId(storeId).orElse(null);
        if (state == null) {
            return 0;
        }
        state.requireCompany(companyId);
        if (state.getStatus() != MemberPointsProjectionStatus.CENTRAL_ACTIVE) {
            return 0;
        }
        int applied = 0;
        Instant now = clock.instant();
        var pending = inbox.findPendingForUpdate(
                storeId, PageRequest.of(0, APPLY_BATCH_SIZE));
        for (var item : pending) {
            item.requireContext(companyId, storeId);
            var member = members.findByIdAndCompanyId(item.getMemberId(), companyId)
                    .orElse(null);
            if (member == null) {
                item.defer(now, "Socio pendiente de sincronizar en la tienda");
                continue;
            }
            member.applyOfficialPoints(
                    item.getPoints(), item.getPointsDebt(), item.getOfficialSyncedAt());
            applyAutomaticCategory(companyId, member);
            item.markApplied(now);
            applied++;
        }
        return applied;
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
