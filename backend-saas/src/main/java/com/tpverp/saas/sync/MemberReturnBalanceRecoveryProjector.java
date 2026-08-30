package com.tpverp.saas.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.loyalty.MemberBalanceType;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccount;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccountRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceLotRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservation;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaim;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimStatus;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceipt;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceiptAlias;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceiptAliasRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceiptRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class MemberReturnBalanceRecoveryProjector {

    static final String ENTITY_TYPE = "MEMBER_RETURN_BALANCE_RECOVERY";

    private final SaasMemberBalanceAccountRepository accounts;
    private final SaasMemberBalanceLotRepository lots;
    private final SaasMemberBalanceRetentionClaimRepository claims;
    private final SaasMemberBalanceRetentionReceiptRepository receipts;
    private final SaasMemberBalanceRetentionReceiptAliasRepository receiptAliases;
    private final ObjectMapper mapper;
    private final SaasMemberBalanceReservationLotRepository reservationLots;
    private final SaasMemberBalanceReservationRepository reservations;

    public MemberReturnBalanceRecoveryProjector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceRetentionClaimRepository claims,
            SaasMemberBalanceRetentionReceiptRepository receipts,
            ObjectMapper mapper) {
        this(accounts, lots, claims, receipts, mapper, null, null, null);
    }

    public MemberReturnBalanceRecoveryProjector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceRetentionClaimRepository claims,
            SaasMemberBalanceRetentionReceiptRepository receipts,
            ObjectMapper mapper,
            SaasMemberBalanceReservationLotRepository reservationLots) {
        this(accounts, lots, claims, receipts, mapper, reservationLots, null, null);
    }

    public MemberReturnBalanceRecoveryProjector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceRetentionClaimRepository claims,
            SaasMemberBalanceRetentionReceiptRepository receipts,
            ObjectMapper mapper,
            SaasMemberBalanceReservationLotRepository reservationLots,
            SaasMemberBalanceReservationRepository reservations) {
        this(accounts, lots, claims, receipts, mapper, reservationLots, reservations, null);
    }

    @Autowired
    public MemberReturnBalanceRecoveryProjector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceRetentionClaimRepository claims,
            SaasMemberBalanceRetentionReceiptRepository receipts,
            ObjectMapper mapper,
            SaasMemberBalanceReservationLotRepository reservationLots,
            SaasMemberBalanceReservationRepository reservations,
            SaasMemberBalanceRetentionReceiptAliasRepository receiptAliases) {
        this.accounts = accounts;
        this.lots = lots;
        this.claims = claims;
        this.receipts = receipts;
        this.receiptAliases = receiptAliases;
        this.mapper = mapper;
        this.reservationLots = reservationLots;
        this.reservations = reservations;
    }

    public boolean supports(String entityType, SyncOperation operation) {
        return ENTITY_TYPE.equals(entityType) && operation == SyncOperation.CONFIRMAR;
    }

    /** Applies the same authoritative reconciliation used by the sync event. */
    @Transactional
    public void reconcile(MemberReturnBalanceRecoveryCommand command, Instant now) {
        apply(command, now);
    }

    /**
     * Domain entry point shared by online finalize and the durable projector.
     * It intentionally receives an already typed, owner-scoped snapshot; JSON
     * parsing and event scope checks remain in {@link #project}.
     */
    @Transactional
    public void apply(MemberReturnBalanceRecoveryCommand command, Instant now) {
        if (command == null || now == null) {
            throw badRequest("El command de recovery es obligatorio");
        }
        if (command.claims() == null || command.claims().stream().anyMatch(Objects::isNull)) {
            throw badRequest("claims debe contener elementos validos");
        }
        List<ClaimInput> inputs = command.claims().stream()
                .map(value -> new ClaimInput(value.lotId(), value.sourceMovementId(),
                        value.sourceDocumentId(), value.amountOriginal(), value.amount()))
                .sorted(Comparator.comparing(value -> value.lotId() == null
                        ? "" : value.lotId().toString()))
                .toList();
        Recovery recovery = new Recovery(command.operationId(), command.companyId(), command.storeId(),
                command.memberId(), command.sourceDocumentId(), command.returnDocumentId(),
                command.attributedAmount(), command.fingerprint(), inputs,
                command.reservationId(), command.reservationSaleId());
        validateTypedRecovery(recovery);
        apply(recovery, now);
    }

    /** Pure validation hook used for idempotent replay checks. */
    public void validate(MemberReturnBalanceRecoveryCommand command) {
        if (command == null || command.claims() == null
                || command.claims().stream().anyMatch(Objects::isNull)) {
            throw badRequest("claims debe contener elementos validos");
        }
        List<ClaimInput> inputs = command.claims().stream()
                .map(value -> new ClaimInput(value.lotId(), value.sourceMovementId(),
                        value.sourceDocumentId(), value.amountOriginal(), value.amount()))
                .sorted(Comparator.comparing(value -> value.lotId() == null
                        ? "" : value.lotId().toString()))
                .toList();
        Recovery recovery = new Recovery(command.operationId(), command.companyId(), command.storeId(),
                command.memberId(), command.sourceDocumentId(), command.returnDocumentId(),
                command.attributedAmount(), command.fingerprint(), inputs,
                command.reservationId(), command.reservationSaleId());
        validateTypedRecovery(recovery);
        if (recovery.attributedAmount().signum() > 0 && recovery.returnDocumentId() == null) {
            throw badRequest("Un recovery positivo nuevo requiere returnDocumentId");
        }
    }

    /**
     * Acquires the exact durable projection-lock set used by {@link #apply}
     * without changing account, lot, claim or receipt state. Online wallet
     * finalization calls this before taking the pessimistic account lock so a
     * recovery event and a finalize cannot invert their lock order.
     */
    public void lockForRecovery(MemberReturnBalanceRecoveryCommand command) {
        if (command == null || command.claims() == null
                || command.claims().stream().anyMatch(Objects::isNull)) {
            throw badRequest("claims debe contener elementos validos");
        }
        List<ClaimInput> inputs = command.claims().stream()
                .map(value -> new ClaimInput(value.lotId(), value.sourceMovementId(),
                        value.sourceDocumentId(), value.amountOriginal(), value.amount()))
                .sorted(Comparator.comparing(value -> value.lotId() == null
                        ? "" : value.lotId().toString()))
                .toList();
        lockKeys(command.companyId(), command.memberId(), command.operationId(),
                command.returnDocumentId(), inputs);
    }

    @Transactional
    public void project(SaasSyncEvent event, Map<String, Object> payload, Instant now) {
        Recovery recovery = parse(payload, event.getEntityId());
        UUID companyId = event.getCompany().getId();
        UUID eventStoreId = event.getStore() == null ? null : event.getStore().getId();
        if (!companyId.equals(recovery.companyId())) {
            throw conflict("El companyId del payload no coincide con el evento");
        }
        if (eventStoreId == null || !eventStoreId.equals(recovery.storeId())) {
            throw conflict("El storeId del payload no coincide con el evento");
        }
        apply(recovery, now);
    }

    private void apply(Recovery recovery, Instant now) {
        UUID companyId = recovery.companyId();
        lockKeys(companyId, recovery.memberId(), recovery.operationId(),
                recovery.returnDocumentId(), recovery.claims());
        String fingerprint = fingerprint(recovery);
        if (!fingerprint.equals(recovery.claimsFingerprint())) {
            throw conflict("El fingerprint de claims de retencion no coincide");
        }
        SaasMemberBalanceRetentionReceipt existing = receipts.findById(recovery.operationId()).orElse(null);
        SaasMemberBalanceRetentionReceiptAlias operationAlias = receiptAliases == null
                ? null : receiptAliases.findById(recovery.operationId()).orElse(null);
        if (existing != null && operationAlias != null) {
            throw conflict("El operationId no puede tener receipt y alias simultaneamente");
        }
        boolean existingViaAlias = operationAlias != null;
        if (existing == null && operationAlias != null) {
            existing = operationAlias.getReceipt();
        }
        SaasMemberBalanceRetentionReceipt existingForReturnDocument = recovery.returnDocumentId() == null
                ? null
                : receipts.findByCompanyIdAndReturnDocumentId(companyId, recovery.returnDocumentId())
                        .orElse(null);
        if (existingForReturnDocument != null
                && !existingForReturnDocument.getOperationId().equals(recovery.operationId())) {
            if (recovery.reservationId() != null) {
                // A prepared reservation is owned by its prepare operation.
                // Cross-operation document deduplication is intentionally
                // limited to standalone delivery/recovery replays: accepting
                // a reservation-bound alias here would leave its HELD_* claims
                // attached to a reservation that may later be consumed.
                throw conflict("Una reserva de saldo no puede reutilizar un receipt de otra operacion");
            }
            if (!existingForReturnDocument.matchesImmutable(companyId, recovery.storeId(),
                    recovery.memberId(), recovery.sourceDocumentId(), recovery.returnDocumentId(),
                    recovery.attributedAmount(), fingerprint)) {
                throw conflict("El receipt del documento de devolucion ya existe con datos diferentes");
            }
            if (existingViaAlias
                    && !existing.getOperationId().equals(existingForReturnDocument.getOperationId())) {
                throw conflict("El alias de operationId no coincide con el receipt del documento");
            }
            requireCompleteReceiptForReplay(existingForReturnDocument);
            if (existing != null && !existingViaAlias) {
                throw conflict("El operationId ya pertenece a otro receipt de retencion");
            }
            if (!existingViaAlias) {
                linkOperationAlias(recovery.operationId(), existingForReturnDocument, now);
            }
            return;
        }
        if (existing != null) {
            if (recovery.reservationId() != null) {
                SaasMemberBalanceAccount replayAccount = accounts
                        .findForUpdate(companyId, recovery.memberId()).orElse(null);
                recoveryReservation(recovery, companyId, replayAccount);
            }
            UUID requestedReturnDocumentId = existingViaAlias && recovery.returnDocumentId() == null
                    ? existing.getReturnDocumentId() : recovery.returnDocumentId();
            if (!existing.matchesImmutable(companyId, recovery.storeId(), recovery.memberId(),
                    recovery.sourceDocumentId(), requestedReturnDocumentId,
                    recovery.attributedAmount(), fingerprint)) {
                throw conflict("El receipt de retencion ya existe con datos diferentes");
            }
            if (existing.getReturnDocumentId() == null && recovery.returnDocumentId() != null) {
                existing.attachReturnDocument(recovery.returnDocumentId(), now);
                receipts.save(existing);
            }
            return;
        }

        if (recovery.returnDocumentId() == null && existing == null
                && recovery.attributedAmount().signum() > 0) {
            throw conflict("Un recovery positivo nuevo requiere returnDocumentId");
        }

        // Phase 1 is deliberately side-effect free. SyncEventService keeps
        // ProjectionException out of rollback, so every input error must be
        // found before a lot, account, claim or receipt is mutated.
        SaasMemberBalanceAccount account = accounts.findForUpdate(companyId, recovery.memberId()).orElse(null);
        rejectOtherLiveReservations(account, recovery, now);
        SaasMemberBalanceReservation recoveryReservation = recoveryReservation(
                recovery, companyId, account);
        List<PlannedClaim> planned = new ArrayList<>();
        BigDecimal recoveredKnown = zero();
        BigDecimal pendingMissing = zero();
        BigDecimal spentShortfall = zero();
        Map<UUID, BigDecimal> preparedRetentionByReservation = new java.util.HashMap<>();
        Map<UUID, SaasMemberBalanceReservation> preparedReservations = new java.util.HashMap<>();
        for (ClaimInput input : recovery.claims()) {
            SaasMemberBalanceLot lot = lots.findById(input.lotId()).orElse(null);
            BigDecimal held;
            SaasMemberBalanceRetentionClaimStatus status;
            if (lot == null) {
                held = input.amount();
                status = SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING;
                pendingMissing = pendingMissing.add(held);
            } else {
                validateLot(lot, companyId, recovery.memberId(), input, recovery.sourceDocumentId());
                BigDecimal heldByOtherReservations = heldByOtherReservations(
                        input, recovery, companyId, recovery.memberId());
                BigDecimal available = lot.getRemainingAmount().subtract(heldByOtherReservations);
                if (available.signum() < 0 || (heldByOtherReservations.signum() > 0
                        && input.amount().compareTo(available) > 0)) {
                    throw conflict("El lote central esta retenido por otra operacion");
                }
                held = input.amount().min(available);
                recoveredKnown = recoveredKnown.add(held);
                spentShortfall = spentShortfall.add(input.amount().subtract(held));
                status = SaasMemberBalanceRetentionClaimStatus.APPLIED;
            }
            SaasMemberBalanceRetentionClaim claim = matchingHeldClaim(
                    input, recovery, companyId, recovery.memberId());
            SaasMemberBalanceReservation linkedReservation = claim == null
                    ? recoveryReservation : claim.getReservation();
            if (claim == null && linkedReservation != null) {
                claim = existingReservationClaim(linkedReservation, input.lotId());
            }
            boolean linkedToReservation = validateReservationCapacity(linkedReservation, lot, held);
            if (linkedReservation != null && linkedReservation.isPrepared()) {
                UUID reservationId = linkedReservation.getId();
                preparedReservations.put(reservationId, linkedReservation);
                preparedRetentionByReservation.merge(
                        reservationId, lot == null || !linkedToReservation ? zero() : held,
                        BigDecimal::add);
            }
            planned.add(new PlannedClaim(input, lot, claim, linkedReservation, held, status));
        }
        for (Map.Entry<UUID, BigDecimal> entry : preparedRetentionByReservation.entrySet()) {
            SaasMemberBalanceReservation prepared = preparedReservations.get(entry.getKey());
            if (prepared.getPreparedLoyaltyAmount().add(entry.getValue())
                    .compareTo(prepared.getReservedLoyaltyAmount()) > 0) {
                throw conflict("El F10 preparado mas la retencion supera la capacidad reservada");
            }
        }
        if (recoveredKnown.signum() > 0 && (account == null
                || account.getBalance().compareTo(recoveredKnown) < 0)) {
            throw conflict("La cuenta central no dispone del saldo retenido");
        }
        if (account == null) {
            account = new SaasMemberBalanceAccount(UUID.randomUUID(), companyId,
                    recovery.memberId(), zero(), zero(), zeroPoints(), now);
        }
        if (recoveryReservation != null && recoveryReservation.isPrepared()) {
            // The final return snapshot may move attribution from one source
            // lot to another. The operation owner is unchanged, so reconcile
            // the prepared reservation only after every claim/capacity check.
            recoveryReservation.reconcilePreparedRetention(
                    recovery.operationId(), fingerprint, recovery.attributedAmount());
        }
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                recovery.operationId(), companyId, recovery.storeId(), recovery.memberId(),
                recovery.sourceDocumentId(), recovery.returnDocumentId(), recovery.attributedAmount(),
                fingerprint, recoveredKnown, pendingMissing, spentShortfall, now);

        // Phase 2 contains only operations already proven safe by phase 1.
        receipt = receipts.save(receipt);
        if (recoveryReservation != null && recovery.claims().isEmpty()) {
            clearActiveRetention(recoveryReservation, now);
        }
        List<ResolvedClaim> resolved = new ArrayList<>();
        for (PlannedClaim plan : planned) {
            if (plan.lot() != null && plan.held().signum() > 0) {
                plan.lot().consume(plan.held());
                account.debit(MemberBalanceType.LOYALTY, plan.held(), now);
                syncReservationLot(plan.reservation(), plan.lot(), plan.held());
            }
            SaasMemberBalanceRetentionClaim claim = plan.claim();
            if (claim == null) {
                claim = new SaasMemberBalanceRetentionClaim(
                        UUID.randomUUID(), plan.reservation(), plan.input().lotId(), plan.input().sourceMovementId(),
                        recovery.sourceDocumentId(), plan.input().amountOriginal(), plan.input().amount(),
                        plan.status(), now);
            } else {
                claim.replace(plan.input().sourceMovementId(), recovery.sourceDocumentId(),
                        plan.input().amountOriginal(), plan.input().amount(), plan.held(), plan.status(), now);
            }
            claim.setHeldAmount(plan.held());
            claim.attachReceipt(receipt);
            claims.save(claim);
            // attachReceipt transfers JPA ownership from reservation to the
            // receipt. Keep the pre-transfer owner for supersede/release.
            resolved.add(new ResolvedClaim(
                    claim, plan.reservation(), plan.held(), plan.status()));
        }
        accounts.save(account);
        supersedeReservationClaims(resolved, companyId, recovery.memberId(), now);
    }

    private void requireCompleteReceiptForReplay(SaasMemberBalanceRetentionReceipt receipt) {
        if (receipt.getRecoveredKnown().compareTo(receipt.getAttributedAmount()) != 0
                || receipt.getPendingMissing().signum() != 0
                || receipt.getSpentShortfall().signum() != 0) {
            throw conflict("El receipt historico aun no tiene una recuperacion completa");
        }
    }

    private void linkOperationAlias(
            UUID operationId, SaasMemberBalanceRetentionReceipt canonical, Instant now) {
        if (receiptAliases == null) {
            // Legacy unit fixtures construct the projector without the alias
            // repository. Production wiring always provides it; retaining the
            // no-op keeps those fixtures focused on debit idempotency.
            return;
        }
        SaasMemberBalanceRetentionReceiptAlias existing = receiptAliases.findById(operationId)
                .orElse(null);
        if (existing != null) {
            if (!existing.getReceipt().getOperationId().equals(canonical.getOperationId())) {
                throw conflict("El alias de operationId ya apunta a otro receipt");
            }
            return;
        }
        receiptAliases.save(new SaasMemberBalanceRetentionReceiptAlias(operationId, canonical, now));
    }

    private void clearActiveRetention(SaasMemberBalanceReservation reservation, Instant now) {
        if (claims != null) {
            List<SaasMemberBalanceRetentionClaim> values =
                    claims.findByReservation_IdOrderByLotIdAsc(reservation.getId());
            if (values != null) {
                values.stream()
                        .filter(value -> value.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN
                                || value.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING)
                        .forEach(value -> value.release(now));
            }
        }
        if (reservation.isActive()) {
            reservation.release(now);
        }
    }

    private SaasMemberBalanceRetentionClaim existingReservationClaim(
            SaasMemberBalanceReservation reservation, UUID lotId) {
        if (claims == null) return null;
        return claims.findByReservation_IdOrderByLotIdAsc(reservation.getId()).stream()
                .filter(value -> value.getLotId().equals(lotId))
                .findFirst().orElse(null);
    }

    private void validateTypedRecovery(Recovery recovery) {
        if (recovery.operationId() == null || recovery.companyId() == null
                || recovery.storeId() == null || recovery.memberId() == null
                || recovery.sourceDocumentId() == null || recovery.claims() == null) {
            throw badRequest("El command de recovery no contiene un propietario completo");
        }
        if ((recovery.reservationId() == null) != (recovery.reservationSaleId() == null)
                || recovery.reservationSaleId() != null && recovery.reservationSaleId().isBlank()) {
            throw badRequest("La identidad de reserva debe venir completa");
        }
        BigDecimal total = zero();
        Set<UUID> lotIds = new HashSet<>();
        for (ClaimInput input : recovery.claims()) {
            if (input.lotId() == null || input.sourceMovementId() == null
                    || input.sourceDocumentId() == null
                    || !lotIds.add(input.lotId())
                    || !recovery.sourceDocumentId().equals(input.sourceDocumentId())
                    || input.amountOriginal() == null || input.amount() == null
                    || input.amountOriginal().scaleByPowerOfTen(0).setScale(2, RoundingMode.UNNECESSARY)
                            .signum() <= 0
                    || input.amount().setScale(2, RoundingMode.UNNECESSARY).signum() <= 0
                    || input.amount().compareTo(input.amountOriginal()) > 0) {
                throw badRequest("Claim de recovery invalido");
            }
            total = total.add(input.amount());
        }
        if (recovery.attributedAmount() == null || recovery.attributedAmount().signum() < 0
                || recovery.attributedAmount().setScale(2, RoundingMode.UNNECESSARY).compareTo(total) != 0) {
            throw badRequest("claims debe igualar attributedAmount");
        }
        if (!fingerprint(recovery).equals(recovery.claimsFingerprint())) {
            throw conflict("El fingerprint de claims de retencion no coincide");
        }
    }

    private SaasMemberBalanceRetentionClaim matchingHeldClaim(
            ClaimInput input, Recovery recovery, UUID companyId, UUID memberId) {
        List<SaasMemberBalanceRetentionClaim> candidates = claims
                .findByLotIdAndSourceMovementIdAndStatusIn(
                        input.lotId(), input.sourceMovementId(),
                        List.of(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN,
                                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING));
        if (candidates == null) return null;
        return candidates.stream()
                .filter(claim -> claim.getReservation() != null)
                .filter(claim -> claim.getReservation().getAccount().getCompanyId().equals(companyId))
                .filter(claim -> claim.getReservation().getAccount().getMemberId().equals(memberId))
                .filter(claim -> sameReservation(claim.getReservation(), recovery))
                .filter(claim -> Objects.equals(claim.getSourceDocumentId(), input.sourceDocumentId()))
                .findFirst().orElse(null);
    }

    private BigDecimal heldByOtherReservations(
            ClaimInput input, Recovery recovery, UUID companyId, UUID memberId) {
        List<SaasMemberBalanceRetentionClaim> candidates = claims
                .findByLotIdAndSourceMovementIdAndStatusIn(
                        input.lotId(), input.sourceMovementId(),
                        List.of(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN,
                                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING));
        if (candidates == null) return zero();
        return candidates.stream()
                .filter(value -> value.getReservation() != null)
                .filter(value -> value.getReservation().getAccount().getCompanyId().equals(companyId)
                        && value.getReservation().getAccount().getMemberId().equals(memberId))
                .filter(value -> !sameReservation(value.getReservation(), recovery))
                .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                .reduce(zero(), BigDecimal::add);
    }

    private void rejectOtherLiveReservations(
            SaasMemberBalanceAccount account, Recovery recovery, Instant now) {
        if (account == null || reservations == null) return;
        List<SaasMemberBalanceReservation> values = reservations.findByAccount_IdAndStatusIn(
                account.getId(), List.of(SaasMemberBalanceReservation.ACTIVE,
                        SaasMemberBalanceReservation.PREPARED));
        if (values == null) return;
        boolean conflictingReservation = values.stream()
                .filter(value -> value.isPrepared()
                        || (value.isActive() && !value.isExpiredAt(now)))
                .anyMatch(value -> !sameReservation(value, recovery));
        if (conflictingReservation) throw conflict("Existe otra reserva activa para el socio");
    }

    private boolean sameReservation(SaasMemberBalanceReservation reservation, Recovery recovery) {
        if (reservation == null) return false;
        if (recovery.reservationId() != null) {
            return reservation.getId().equals(recovery.reservationId())
                    && recovery.reservationSaleId() != null
                    && recovery.reservationSaleId().equals(reservation.getSaleId())
                    && (reservation.preparedBy(recovery.operationId())
                        || recovery.operationId().toString().equals(reservation.getSaleId()));
        }
        // An ACTIVE reservation cannot be claimed by inference from a sale or
        // operation id; the publisher must include the durable reservation
        // identity and exact sale owner. Prepared reservations are already
        // bound to the operation id by the prepare protocol.
        return reservation.isPrepared() && reservation.preparedBy(recovery.operationId());
    }

    private boolean validateReservationCapacity(
            SaasMemberBalanceReservation reservation, SaasMemberBalanceLot lot, BigDecimal held) {
        if (reservation == null || lot == null || held.signum() == 0) return false;
        if (!reservation.isPrepared() || reservationLots == null) return false;
        SaasMemberBalanceReservationLot link = reservationLots
                .findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()).stream()
                .filter(value -> value.getLot().getId().equals(lot.getId()))
                .findFirst().orElse(null);
        // A lot credited after the reservation was prepared is intentionally
        // outside its reserved bucket. It is still a valid retention source;
        // the aggregate prepared+known capacity check below protects F10.
        if (link == null) return false;
        if (link.getRemainingAmount().compareTo(held) < 0) {
            throw conflict("El lote reservado no cubre la recuperacion");
        }
        return true;
    }

    private SaasMemberBalanceReservation recoveryReservation(
            Recovery recovery, UUID companyId, SaasMemberBalanceAccount account) {
        if (recovery.reservationId() == null) return null;
        if (reservations == null || account == null) {
            throw conflict("No se puede validar la reserva de recovery");
        }
        SaasMemberBalanceReservation reservation = reservations.findById(recovery.reservationId())
                .orElse(null);
        if (reservation == null) {
            throw conflict("La reserva de recovery no existe");
        }
        if (!sameReservation(reservation, recovery)
                || !companyId.equals(reservation.getAccount().getCompanyId())
                || !recovery.memberId().equals(reservation.getAccount().getMemberId())
                || !recovery.storeId().equals(reservation.getStoreId())
                || !account.getId().equals(reservation.getAccount().getId())) {
            throw conflict("La reserva de retencion no pertenece a la operacion");
        }
        return reservation;
    }

    private void syncReservationLot(
            SaasMemberBalanceReservation reservation, SaasMemberBalanceLot lot, BigDecimal amount) {
        if (reservation == null || lot == null || amount.signum() == 0 || reservationLots == null) return;
        if (reservation == null || !reservation.isPrepared()) return;
        reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()).stream()
                .filter(value -> value.getLot().getId().equals(lot.getId()))
                .findFirst()
                .ifPresent(value -> value.consume(amount));
    }

    private void supersedeReservationClaims(
            List<ResolvedClaim> resolved, UUID companyId, UUID memberId, Instant now) {
        Set<UUID> usedReservations = resolved.stream()
                .map(ResolvedClaim::reservation)
                .filter(Objects::nonNull)
                .map(SaasMemberBalanceReservation::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (UUID reservationId : usedReservations) {
            SaasMemberBalanceRetentionClaim first = resolved.stream()
                    .filter(value -> value.reservation() != null
                            && value.reservation().getId().equals(reservationId))
                    .map(ResolvedClaim::claim)
                    .findFirst().orElse(null);
            if (first == null) continue;
            List<SaasMemberBalanceRetentionClaim> reservationClaims =
                    claims.findByReservation_IdOrderByLotIdAsc(reservationId);
            reservationClaims.stream()
                    .filter(value -> value.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN
                            || value.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING)
                    .filter(value -> !resolved.stream().map(ResolvedClaim::claim).toList().contains(value))
                    .forEach(value -> value.release(now));
            SaasMemberBalanceReservation reservation = resolved.stream()
                    .filter(value -> value.claim() == first)
                    .map(ResolvedClaim::reservation)
                    .findFirst().orElse(null);
            if (reservation == null) continue;
            if (reservation.isActive()) reservation.release(now);
        }
    }

    private void validateLot(
            SaasMemberBalanceLot lot, UUID companyId, UUID memberId,
            ClaimInput input, UUID sourceDocumentId) {
        if (!companyId.equals(lot.getCompanyId()) || !memberId.equals(lot.getMemberId())
                || lot.getBalanceType() != MemberBalanceType.LOYALTY
                || !input.lotId().equals(lot.getId())
                || !input.sourceMovementId().equals(lot.getSourceMovementId())
                || !Objects.equals(sourceDocumentId, lot.getDocumentId())
                || input.amountOriginal().compareTo(lot.getOriginalAmount()) != 0) {
            throw conflict("El claim no coincide con el lote central");
        }
    }

    private void lockKeys(UUID companyId, UUID memberId, UUID operationId, UUID returnDocumentId,
            List<ClaimInput> inputs) {
        List<String> keys = new ArrayList<>();
        keys.add("COMPANY:" + companyId);
        keys.add("ACCOUNT:" + companyId + ":" + memberId);
        keys.add("OPERATION:" + operationId);
        if (returnDocumentId != null) {
            keys.add("RETURN_DOCUMENT:" + companyId + ":" + returnDocumentId);
        }
        inputs.forEach(input -> {
            keys.add("LOT:" + input.lotId());
            keys.add("SOURCE:" + companyId + ":" + input.sourceMovementId());
        });
        keys.stream().distinct()
                .sorted(Comparator.comparingInt((String key) -> key.startsWith("OPERATION:") ? 0 : 1)
                        .thenComparing(String::compareTo))
                .forEach(key -> {
                    accounts.ensureProjectionLock(key);
                    accounts.lockProjectionKey(key);
                });
    }


    private Recovery parse(Map<String, Object> payload, UUID operationId) {
        if (!number(payload.get("schemaVersion"), "schemaVersion").equals(BigDecimal.ONE)) {
            throw badRequest("schemaVersion debe ser 1");
        }
        UUID companyId = requiredUuid(payload.get("companyId"), "companyId");
        UUID storeId = requiredUuid(payload.get("storeId"), "storeId");
        UUID memberId = requiredUuid(payload.get("memberId"), "memberId");
        UUID sourceDocumentId = requiredUuid(payload.get("sourceDocumentId"), "sourceDocumentId");
        UUID returnDocumentId = optionalUuid(payload.get("returnDocumentId"), "returnDocumentId");
        UUID reservationId = optionalUuid(payload.get("reservationId"), "reservationId");
        String reservationSaleId = optionalText(payload.get("reservationSaleId"));
        if ((reservationId == null) != (reservationSaleId == null)) {
            throw badRequest("La identidad de reserva debe venir completa");
        }
        BigDecimal attributed = nonNegative(money(payload.get("attributedAmount"), "attributedAmount"));
        String suppliedFingerprint = requiredText(payload.get("claimsFingerprint"), "claimsFingerprint");
        Object rawClaims = payload.get("claims");
        if (!(rawClaims instanceof List<?> values)) {
            throw badRequest("claims debe ser una lista");
        }
        List<ClaimInput> inputs = new ArrayList<>();
        Set<UUID> lotIds = new HashSet<>();
        BigDecimal total = zero();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> value)) throw badRequest("claim invalido");
            UUID lotId = requiredUuid(value.get("lotId"), "lotId");
            if (!lotIds.add(lotId)) throw badRequest("No puede repetirse lotId en un receipt");
            UUID movementId = requiredUuid(value.get("sourceMovementId"), "sourceMovementId");
            UUID documentId = requiredUuid(value.get("sourceDocumentId"), "sourceDocumentId");
            if (!sourceDocumentId.equals(documentId)) throw conflict("sourceDocumentId no coincide");
            BigDecimal original = positive(money(value.get("amountOriginal"), "amountOriginal"));
            BigDecimal amount = positive(money(value.get("amount"), "amount"));
            if (amount.compareTo(original) > 0) throw badRequest("amount supera amountOriginal");
            total = total.add(amount);
            inputs.add(new ClaimInput(lotId, movementId, documentId, original, amount));
        }
        if (total.compareTo(attributed) != 0) throw badRequest("claims debe igualar attributedAmount");
        inputs.sort(Comparator.comparing(value -> value.lotId().toString()));
        return new Recovery(operationId, companyId, storeId, memberId, sourceDocumentId,
                returnDocumentId, attributed, suppliedFingerprint, inputs,
                reservationId, reservationSaleId);
    }

    private String fingerprint(Recovery recovery) {
        String canonical = recovery.attributedAmount().toPlainString() + "\n"
                + recovery.claims().stream().map(value -> value.lotId() + "|"
                        + value.sourceMovementId() + "|" + value.sourceDocumentId() + "|"
                        + value.amountOriginal().toPlainString() + "|" + value.amount().toPlainString())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular fingerprint", exception);
        }
    }

    private static BigDecimal money(Object raw, String field) {
        return number(raw, field).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal number(Object raw, String field) {
        if (!(raw instanceof Number) && !(raw instanceof String)) throw badRequest(field + " invalido");
        try { return new BigDecimal(raw.toString()); }
        catch (NumberFormatException exception) { throw badRequest(field + " invalido"); }
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value.signum() <= 0) throw badRequest("El importe debe ser positivo");
        return value;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value.signum() < 0) throw badRequest("El importe no puede ser negativo");
        return value;
    }

    private static UUID requiredUuid(Object raw, String field) {
        UUID value = optionalUuid(raw, field);
        if (value == null) throw badRequest(field + " es obligatorio");
        return value;
    }

    private static UUID optionalUuid(Object raw, String field) {
        if (raw == null) return null;
        try { return UUID.fromString(raw.toString()); }
        catch (IllegalArgumentException exception) { throw badRequest(field + " debe ser UUID"); }
    }

    private static String requiredText(Object raw, String field) {
        if (!(raw instanceof String value) || value.isBlank()) throw badRequest(field + " es obligatorio");
        return value.trim();
    }

    private static String optionalText(Object raw) {
        return raw instanceof String value && !value.isBlank() ? value.trim() : null;
    }

    private static ProjectionException badRequest(String message) {
        return new ProjectionException(HttpStatus.BAD_REQUEST, message);
    }

    private static ProjectionException conflict(String message) {
        return new ProjectionException(HttpStatus.CONFLICT, message);
    }

    private static BigDecimal zero() { return BigDecimal.ZERO.setScale(2); }
    private static BigDecimal zeroPoints() { return BigDecimal.ZERO.setScale(4); }

    private record Recovery(
            UUID operationId, UUID companyId, UUID storeId, UUID memberId,
            UUID sourceDocumentId, UUID returnDocumentId, BigDecimal attributedAmount,
            String claimsFingerprint, List<ClaimInput> claims,
            UUID reservationId, String reservationSaleId) {}

    private record ClaimInput(
            UUID lotId, UUID sourceMovementId, UUID sourceDocumentId,
            BigDecimal amountOriginal, BigDecimal amount) {}

    private record ResolvedClaim(
            SaasMemberBalanceRetentionClaim claim,
            SaasMemberBalanceReservation reservation,
            BigDecimal held,
            SaasMemberBalanceRetentionClaimStatus status) {}

    private record PlannedClaim(
            ClaimInput input,
            SaasMemberBalanceLot lot,
            SaasMemberBalanceRetentionClaim claim,
            SaasMemberBalanceReservation reservation,
            BigDecimal held,
            SaasMemberBalanceRetentionClaimStatus status) {}

    public static final class ProjectionException extends ResponseStatusException {
        private ProjectionException(HttpStatus status, String reason) {
            super(status, reason);
        }
    }
}
