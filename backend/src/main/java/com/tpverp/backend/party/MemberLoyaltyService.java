package com.tpverp.backend.party;

import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.DocumentLineCommand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberLoyaltyService {

    private final MemberRepository members;
    private final MemberCategoryRepository categories;
    private final MemberSettingsRepository settings;
    private final MemberMovementRepository movements;
    private final MemberBalanceLotRepository lots;
    private final MemberBalanceLotConsumptionRepository lotConsumptions;
    private final MemberDocumentLoyaltySettlementRepository loyaltySettlements;
    private final MemberDocumentLoyaltyLineRepository loyaltyLines;
    private final MemberCardDeliveryRepository cardDeliveries;
    private final MemberSmtpSettingsRepository smtpSettings;
    private final CommercialContactChannelRepository channels;
    private final SyncOutboxService syncOutbox;
    private final PartyContext context;
    private final Clock clock;

    public MemberLoyaltyService(
            MemberRepository members,
            MemberCategoryRepository categories,
            MemberSettingsRepository settings,
            MemberMovementRepository movements,
            MemberBalanceLotRepository lots,
            MemberBalanceLotConsumptionRepository lotConsumptions,
            MemberDocumentLoyaltySettlementRepository loyaltySettlements,
            MemberDocumentLoyaltyLineRepository loyaltyLines,
            MemberCardDeliveryRepository cardDeliveries,
            MemberSmtpSettingsRepository smtpSettings,
            CommercialContactChannelRepository channels,
            SyncOutboxService syncOutbox,
            PartyContext context,
            Clock clock) {
        this.members = members;
        this.categories = categories;
        this.settings = settings;
        this.movements = movements;
        this.lots = lots;
        this.lotConsumptions = lotConsumptions;
        this.loyaltySettlements = loyaltySettlements;
        this.loyaltyLines = loyaltyLines;
        this.cardDeliveries = cardDeliveries;
        this.smtpSettings = smtpSettings;
        this.channels = channels;
        this.syncOutbox = syncOutbox;
        this.context = context;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MemberView get(UUID id) {
        return MemberView.from(member(id));
    }

    @Transactional(readOnly = true)
    public List<MemberDirectoryView> list() {
        return members.findByCompanyIdOrderByCustomerFiscalNameAsc(context.currentCompany().getId())
                .stream()
                .map(MemberDirectoryView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemberMovementView> movements(UUID memberId) {
        var member = member(memberId);
        return movements.findByMemberIdOrderByCreatedAtDesc(member.getId()).stream()
                .map(MemberMovementView::from)
                .toList();
    }

    @Transactional
    public MemberMovementView adjustBalance(UUID memberId, BigDecimal amount, String reason) {
        var member = member(memberId);
        member.applyBalance(amount);
        return movement(member, MemberMovementType.AJUSTE_MANUAL_SALDO, amount, 0, null, null, reason);
    }

    @Transactional
    public MemberMovementView adjustPoints(UUID memberId, long points, String reason) {
        var member = member(memberId);
        member.applyPoints(points);
        autoCategory(member);
        return movement(member, MemberMovementType.AJUSTE_MANUAL_PUNTOS, BigDecimal.ZERO, points, null, null, reason);
    }

    @Transactional
    public void recordPaidSale(CommercialDocument document, BigDecimal paidAmount) {
        var paid = PartyValues.money(paidAmount);
        recordPaidSale(document, new LoyaltyAccrual(paid, paid, paid, Map.of()));
    }

    @Transactional
    public void recordPaidSale(CommercialDocument document, LoyaltyAccrual accrual) {
        if (!isSaleForAccrual(document) || document.getClienteId() == null) {
            return;
        }
        var member = members.findByCustomerIdAndCompanyId(
                document.getClienteId(), context.currentCompany().getId())
                .filter(Member::isActive)
                .orElse(null);
        if (member == null) {
            return;
        }
        var now = Instant.now(clock);
        var storedSettlement = loyaltySettlements.findById(document.getId());
        var settlement = storedSettlement
                .orElseGet(() -> new MemberDocumentLoyaltySettlement(
                        document.getId(), member, accrual.documentAmount(),
                        accrual.eligibleDocumentAmount(), now));
        if (!settlement.getMember().getId().equals(member.getId())) {
            throw new IllegalStateException(
                    "El documento ya tiene una liquidacion de otro miembro");
        }
        settlement.verifyAndUpdateEligibility(
                accrual.documentAmount(), accrual.eligibleDocumentAmount(), now);
        if (storedSettlement.isEmpty()) {
            // The line snapshot has a strict FK to this aggregate root. Flush the
            // new parent first so Hibernate cannot queue child inserts ahead of it.
            loyaltySettlements.saveAndFlush(settlement);
        }
        saveEligibilitySnapshot(document, accrual);
        var balanceUsed = movements.findByDocumentIdOrderByCreatedAtAsc(document.getId())
                .stream()
                .filter(movement -> movement.getType() == MemberMovementType.USO_SALDO)
                .map(MemberMovement::getBalanceAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        settlement.updateMemberBalanceUsed(balanceUsed, now);
        var config = settings.findById(context.currentCompany().getId())
                .orElseGet(() -> new MemberSettings(context.currentCompany()));
        var paid = PartyValues.money(
                accrual.eligiblePaidAmount().subtract(
                        settlement.getEligiblePaidAmount()));
        if (paid.signum() < 0) {
            throw new IllegalStateException(
                    "El importe elegible cobrado acumulado no puede disminuir");
        }
        if (paid.signum() <= 0) {
            loyaltySettlements.save(settlement);
            return;
        }
        var points = paid.multiply(config.getPointsPerEuro()).setScale(0, RoundingMode.FLOOR).longValue();
        var balance = paid.multiply(config.getBalanceAccrualPercent())
                .movePointLeft(2)
                .setScale(2, RoundingMode.DOWN);
        long repaidPoints = 0;
        long availablePoints = 0;
        if (points > 0) {
            repaidPoints = member.repayPointsDebt(points);
            if (repaidPoints > 0) {
                movement(member, document.getId(), MemberMovementType.PAGO_DEUDA_PUNTOS,
                        BigDecimal.ZERO, repaidPoints, null, null,
                        "compensacion de deuda con puntos generados");
            }
            availablePoints = points - repaidPoints;
            if (availablePoints > 0) {
                member.applyPoints(availablePoints);
                movement(member, document.getId(), MemberMovementType.ACUMULACION_PUNTOS,
                        BigDecimal.ZERO, availablePoints, null, null, "documento cobrado");
            }
            autoCategory(member);
        }
        var repaidBalance = BigDecimal.ZERO.setScale(2);
        var availableBalance = BigDecimal.ZERO.setScale(2);
        if (balance.signum() > 0) {
            repaidBalance = member.repayBalanceDebt(balance);
            if (repaidBalance.signum() > 0) {
                movement(member, document.getId(), MemberMovementType.PAGO_DEUDA_SALDO,
                        repaidBalance, 0, null, null,
                        "compensacion de deuda con saldo generado");
            }
            availableBalance = PartyValues.money(balance.subtract(repaidBalance));
            if (availableBalance.signum() > 0) {
                member.applyBalance(availableBalance);
                var movement = saveMovement(member, document.getId(), MemberMovementType.ACUMULACION_SALDO,
                        availableBalance, 0, null, null, "documento cobrado");
                lots.save(new MemberBalanceLot(member, movement, availableBalance, Instant.now(clock),
                        expiration(config.getBalanceExpirationPolicy())));
            }
        }
        settlement.recordAccrual(
                paid,
                points,
                availablePoints,
                repaidPoints,
                balance,
                availableBalance,
                repaidBalance,
                now);
        loyaltySettlements.save(settlement);
    }
    // Accrues benefits from actual collected amount, not from document total.

    @Transactional
    public void activateMember(Member member) {
        var previous = member.getMemberCategory() == null ? null : member.getMemberCategory().getId();
        member.activate();
        if (member.getMemberCategory() == null) {
            initialCategory().ifPresent(category -> member.setCategory(category, false));
        }
        movement(member, MemberMovementType.ALTA_MIEMBRO, BigDecimal.ZERO, 0,
                previous, member.getMemberCategory() == null ? null : member.getMemberCategory().getId(),
                "alta miembro");
        enqueueWelcomeCard(member);
    }

    @Transactional
    public void deactivateMember(Member member) {
        member.deactivate();
        movement(member, MemberMovementType.DESACTIVACION_MIEMBRO, BigDecimal.ZERO, 0,
                member.getMemberCategory() == null ? null : member.getMemberCategory().getId(),
                null, "desactivacion miembro");
    }

    @Transactional
    public BigDecimal consumeBalanceForPayment(CommercialDocument document, BigDecimal amount) {
        if (document.getClienteId() == null) {
            throw new IllegalArgumentException("message.member.customer_required_for_balance_payment");
        }
        var member = members.findByCustomerIdAndCompanyId(
                document.getClienteId(), context.currentCompany().getId())
                .filter(Member::isActive)
                .orElseThrow(() -> new IllegalArgumentException("message.member.not_found"));
        requireRecentOfficialSync(member);
        var value = PartyValues.money(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("message.member.balance_payment_positive_required");
        }
        member.applyBalance(value.negate());
        var movement = saveMovement(member, document.getId(), MemberMovementType.USO_SALDO,
                value.negate(), 0, null, null, "pago con saldo miembro");
        consumeLots(member, movement, value);
        return value;
    }
    // Consumes member balance against FIFO earned lots after checking the SaaS snapshot is recent.

    @Transactional
    public void reverseConfirmedReturn(
            CommercialDocument original,
            CommercialDocument returnDocument,
            BigDecimal cumulativeRefund,
            BigDecimal cumulativeEligibleRefund) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(returnDocument, "returnDocument");
        if (original.getId().equals(returnDocument.getId())) {
            throw new IllegalArgumentException(
                    "La devolucion no puede ser su propio documento de origen");
        }
        var settlement = loyaltySettlements.findById(original.getId()).orElse(null);
        if (settlement == null) {
            return;
        }
        var now = Instant.now(clock);
        var plan = settlement.planReversal(
                cumulativeRefund, cumulativeEligibleRefund);
        var member = settlement.getMember();
        long pointsDebtCreated = 0;
        var balanceDebtCreated = BigDecimal.ZERO.setScale(2);

        var reversedPointsNow = plan.grantedPointsDelta() + plan.debtPointsDelta();
        if (reversedPointsNow > 0) {
            var removable = Math.min(member.getMemberPoints(), plan.grantedPointsDelta());
            if (removable > 0) {
                member.applyPoints(-removable);
            }
            pointsDebtCreated = Math.addExact(
                    plan.debtPointsDelta(), plan.grantedPointsDelta() - removable);
            movement(
                    member,
                    returnDocument.getId(),
                    MemberMovementType.DEVOLUCION_ACUMULACION_PUNTOS,
                    BigDecimal.ZERO,
                    -reversedPointsNow,
                    null,
                    null,
                    "devolucion del documento " + original.getNumero());
        }

        var reversedBalanceNow = plan.grantedBalanceDelta()
                .add(plan.debtBalanceDelta());
        if (reversedBalanceNow.signum() > 0) {
            var reversalMovement = saveMovement(
                    member,
                    returnDocument.getId(),
                    MemberMovementType.DEVOLUCION_ACUMULACION_SALDO,
                    reversedBalanceNow.negate(),
                    0,
                    null,
                    null,
                    "devolucion del documento " + original.getNumero());
            var sourceLots = originalAccrualLots(original.getId());
            var cancelledAvailable = cancelAvailableAccrual(
                    sourceLots, reversalMovement, plan.grantedBalanceDelta());
            if (cancelledAvailable.signum() > 0) {
                member.applyReturnBalance(cancelledAvailable.negate());
            }
            var unavailableGranted = PartyValues.money(
                    plan.grantedBalanceDelta().subtract(cancelledAvailable));
            var previouslyCreatedForSpentBalance = PartyValues.money(
                    settlement.getReturnBalanceDebtCreated()
                            .subtract(settlement.getReversedOriginalBalanceDebt()))
                    .max(BigDecimal.ZERO.setScale(2));
            var spentCapacity = PartyValues.money(
                    originalAccrualSpentAmount(sourceLots)
                            .subtract(previouslyCreatedForSpentBalance))
                    .max(BigDecimal.ZERO.setScale(2));
            var spentGranted = unavailableGranted.min(spentCapacity);
            balanceDebtCreated = plan.debtBalanceDelta().add(spentGranted);
        }

        if (pointsDebtCreated > 0 || balanceDebtCreated.signum() > 0) {
            member.addLoyaltyDebt(balanceDebtCreated, pointsDebtCreated);
        }

        if (plan.memberBalanceRestoreDelta().signum() > 0) {
            var restored = restoreOriginalBalanceUsage(
                    original.getId(),
                    settlement.getRestoredMemberBalance(),
                    plan.memberBalanceRestoreDelta());
            member.applyReturnBalance(restored);
            movement(
                    member,
                    returnDocument.getId(),
                    MemberMovementType.DEVOLUCION_RESTAURACION_SALDO,
                    restored,
                    0,
                    null,
                    null,
                    "restauracion por devolucion del documento " + original.getNumero());
        }

        if (reversedPointsNow > 0) {
            autoCategory(member);
        }
        settlement.recordReversal(
                plan, pointsDebtCreated, balanceDebtCreated, now);
        loyaltySettlements.save(settlement);
    }

    private List<MemberBalanceLot> originalAccrualLots(UUID documentId) {
        return movements.findByDocumentIdOrderByCreatedAtAsc(documentId).stream()
                .filter(movement ->
                        movement.getType() == MemberMovementType.ACUMULACION_SALDO)
                .flatMap(movement -> lots.findBySourceMovement_Id(movement.getId()).stream())
                .sorted(Comparator.comparing(MemberBalanceLot::getCreatedAt)
                        .thenComparing(MemberBalanceLot::getId))
                .toList();
    }

    private BigDecimal cancelAvailableAccrual(
            List<MemberBalanceLot> sourceLots,
            MemberMovement reversalMovement,
            BigDecimal requested) {
        var remaining = PartyValues.money(requested);
        var cancelled = BigDecimal.ZERO.setScale(2);
        for (var lot : sourceLots) {
            if (remaining.signum() <= 0) {
                break;
            }
            var amount = lot.getAmountRemaining().min(remaining);
            if (amount.signum() <= 0) {
                continue;
            }
            lot.consume(amount);
            lotConsumptions.save(new MemberBalanceLotConsumption(
                    reversalMovement, lot, amount));
            cancelled = cancelled.add(amount);
            remaining = remaining.subtract(amount);
        }
        return PartyValues.money(cancelled);
    }

    private BigDecimal originalAccrualSpentAmount(
            List<MemberBalanceLot> sourceLots) {
        return PartyValues.money(sourceLots.stream()
                .flatMap(lot -> lotConsumptions.findByLot_Id(lot.getId()).stream())
                .filter(consumption -> consumption.getMovement().getType()
                        == MemberMovementType.USO_SALDO)
                .map(MemberBalanceLotConsumption::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal restoreOriginalBalanceUsage(
            UUID documentId,
            BigDecimal alreadyRestored,
            BigDecimal requested) {
        var skip = PartyValues.money(alreadyRestored);
        var remaining = PartyValues.money(requested);
        var consumptions = movements.findByDocumentIdOrderByCreatedAtAsc(documentId).stream()
                .filter(movement -> movement.getType() == MemberMovementType.USO_SALDO)
                .flatMap(movement -> lotConsumptions.findByMovement_Id(movement.getId()).stream())
                .sorted(Comparator
                        .comparing((MemberBalanceLotConsumption value) ->
                                value.getLot().getCreatedAt())
                        .thenComparing(value -> value.getLot().getId()))
                .toList();
        for (var consumption : consumptions) {
            if (remaining.signum() <= 0) {
                break;
            }
            if (skip.compareTo(consumption.getAmount()) >= 0) {
                skip = skip.subtract(consumption.getAmount());
                continue;
            }
            var available = consumption.getAmount().subtract(skip);
            skip = BigDecimal.ZERO.setScale(2);
            var amount = available.min(remaining);
            consumption.getLot().restore(amount);
            remaining = remaining.subtract(amount);
        }
        if (remaining.signum() > 0) {
            throw new IllegalStateException(
                    "No se puede reconstruir el saldo de miembro usado por el documento");
        }
        return PartyValues.money(requested);
    }

    @Transactional(readOnly = true)
    public void validateTicketCancellation(CommercialDocument document) {
        var all = movements.findByDocumentIdOrderByCreatedAtAsc(document.getId());
        var originals = all.stream()
                .filter(movement -> movement.getType() == MemberMovementType.ACUMULACION_PUNTOS
                        || movement.getType() == MemberMovementType.ACUMULACION_SALDO
                        || movement.getType() == MemberMovementType.USO_SALDO)
                .toList();
        if (originals.isEmpty()) {
            return;
        }
        var member = originals.getFirst().getMember();
        var restoredUsage = originals.stream()
                .filter(movement -> movement.getType() == MemberMovementType.USO_SALDO)
                .map(MemberMovement::getBalanceAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var cancelledAccrual = originals.stream()
                .filter(movement -> movement.getType() == MemberMovementType.ACUMULACION_SALDO)
                .map(MemberMovement::getBalanceAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var cancelledPoints = originals.stream()
                .filter(movement -> movement.getType() == MemberMovementType.ACUMULACION_PUNTOS)
                .mapToLong(MemberMovement::getPointsAmount)
                .sum();
        if (member.getMemberBalance().add(restoredUsage).compareTo(cancelledAccrual) < 0) {
            throw new IllegalStateException(
                    "el saldo generado por el ticket ya fue utilizado");
        }
        if (member.getMemberPoints() < cancelledPoints) {
            throw new IllegalStateException(
                    "los puntos generados por el ticket ya fueron utilizados");
        }
        for (var movement : originals) {
            if (movement.getType() == MemberMovementType.ACUMULACION_SALDO) {
                var sourceLots = lots.findBySourceMovement_Id(movement.getId());
                if (sourceLots.size() != 1
                        || sourceLots.getFirst().getAmountOriginal()
                                .compareTo(movement.getBalanceAmount()) != 0
                        || sourceLots.getFirst().getAmountRemaining()
                                .compareTo(movement.getBalanceAmount()) != 0) {
                    throw new IllegalStateException(
                            "el saldo generado por el ticket ya fue utilizado");
                }
            } else if (movement.getType() == MemberMovementType.USO_SALDO) {
                var consumed = lotConsumptions.findByMovement_Id(movement.getId()).stream()
                        .map(MemberBalanceLotConsumption::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (consumed.compareTo(movement.getBalanceAmount().abs()) != 0) {
                    throw new IllegalStateException(
                            "no se puede reconstruir el saldo de miembro consumido");
                }
            }
        }
    }

    @Transactional
    public void compensateTicketCancellation(CommercialDocument document) {
        validateTicketCancellation(document);
        var all = movements.findByDocumentIdOrderByCreatedAtAsc(document.getId());
        var originals = all.stream()
                .filter(movement -> movement.getType() == MemberMovementType.ACUMULACION_PUNTOS
                        || movement.getType() == MemberMovementType.ACUMULACION_SALDO
                        || movement.getType() == MemberMovementType.USO_SALDO)
                .toList();
        if (originals.isEmpty()) {
            return;
        }
        var member = originals.getFirst().getMember();
        var reason = "anulacion del ticket " + document.getNumero();

        if (all.stream().noneMatch(movement ->
                movement.getType() == MemberMovementType.ANULACION_USO_SALDO)) {
            var restored = BigDecimal.ZERO;
            for (var movement : originals) {
                if (movement.getType() != MemberMovementType.USO_SALDO) {
                    continue;
                }
                for (var consumption : lotConsumptions.findByMovement_Id(movement.getId())) {
                    consumption.getLot().restore(consumption.getAmount());
                    restored = restored.add(consumption.getAmount());
                }
            }
            if (restored.signum() > 0) {
                member.applyBalance(restored);
                movement(member, document.getId(),
                        MemberMovementType.ANULACION_USO_SALDO,
                        restored, 0, null, null, reason);
            }
        }

        if (all.stream().noneMatch(movement ->
                movement.getType() == MemberMovementType.ANULACION_ACUMULACION_SALDO)) {
            var cancelled = BigDecimal.ZERO;
            for (var movement : originals) {
                if (movement.getType() != MemberMovementType.ACUMULACION_SALDO) {
                    continue;
                }
                var lot = lots.findBySourceMovement_Id(movement.getId()).getFirst();
                lot.cancelUnspentAccrual(movement.getBalanceAmount());
                cancelled = cancelled.add(movement.getBalanceAmount());
            }
            if (cancelled.signum() > 0) {
                member.applyBalance(cancelled.negate());
                movement(member, document.getId(),
                        MemberMovementType.ANULACION_ACUMULACION_SALDO,
                        cancelled.negate(), 0, null, null, reason);
            }
        }

        if (all.stream().noneMatch(movement ->
                movement.getType() == MemberMovementType.ANULACION_ACUMULACION_PUNTOS)) {
            var cancelled = originals.stream()
                    .filter(movement ->
                            movement.getType() == MemberMovementType.ACUMULACION_PUNTOS)
                    .mapToLong(MemberMovement::getPointsAmount)
                    .sum();
            if (cancelled > 0) {
                member.applyPoints(-cancelled);
                autoCategory(member);
                movement(member, document.getId(),
                        MemberMovementType.ANULACION_ACUMULACION_PUNTOS,
                        BigDecimal.ZERO, -cancelled, null, null, reason);
            }
        }
    }

    @Transactional
    public MemberView applyOfficialState(OfficialMemberStateCommand command) {
        if (movements.existsBySourceEventId(command.sourceEventId())) {
            return MemberView.from(member(command.memberId()));
        }
        var member = member(command.memberId());
        var previous = member.getMemberCategory() == null ? null : member.getMemberCategory().getId();
        var category = command.categoryId() == null ? null : category(command.categoryId());
        member.applyOfficialState(command.balance(), command.points(), category, command.syncedAt());
        var movement = new MemberMovement(
                member, context.currentStore(), context.currentUser(), MemberMovementType.AJUSTE_SAAS,
                BigDecimal.ZERO, 0, previous, command.categoryId(), "estado oficial SaaS",
                Instant.now(clock));
        movement.setSourceEventId(command.sourceEventId());
        movements.save(movement);
        return MemberView.from(member);
    }
    // Applies an authoritative SaaS snapshot once per source event.

    @Transactional
    public int expireBalanceLots() {
        int expiredCount = 0;
        var now = Instant.now(clock);
        for (var lot : lots.findByExpiresAtBeforeAndExpiredAtIsNullAndAmountRemainingGreaterThan(
                now, BigDecimal.ZERO)) {
            var amount = lot.expire(now);
            if (amount.signum() <= 0) {
                continue;
            }
            lot.getMember().expireBalance(amount);
            movement(lot.getMember(), MemberMovementType.CADUCIDAD_SALDO,
                    amount.negate(), 0, null, null, "caducidad saldo");
            expiredCount++;
        }
        return expiredCount;
    }
    // Expires remaining balances from lots whose expiration date has passed.

    @Transactional(readOnly = true)
    public DocumentLineCommand applyLineBenefit(
            UUID customerId, DocumentLineCommand line, Product product) {
        if (customerId == null) {
            return line;
        }
        var member = members.findByCustomerIdAndCompanyId(customerId, context.currentCompany().getId())
                .filter(Member::isActive)
                .orElse(null);
        if (member == null) {
            return line;
        }
        var priced = line;
        var memberPrice = product.getMemberPrice();
        if (product.getDiscountType() == DiscountType.MEMBER_PRICE
                && memberPrice != null && memberPrice.signum() > 0) {
            priced = priced.withPrice(memberPrice, "MEMBER");
        }
        var category = member.getMemberCategory();
        if (category == null || !category.isActive() || !category.isDiscountEnabled()) {
            return priced;
        }
        var discount = line.descuento().max(category.getDiscountPercent());
        return priced.withDiscount(discount, "MEMBER");
    }
    // Resolves member state once, then combines product pricing with the strongest line discount.

    @Transactional
    public MemberView setCategory(UUID memberId, UUID categoryId, boolean lockAutomatic, String reason) {
        var member = member(memberId);
        UUID previous = member.getMemberCategory() == null ? null : member.getMemberCategory().getId();
        var category = categoryId == null ? null : category(categoryId);
        if (category != null && category.isManualOnly() && !context.currentUser().isProtegido()) {
            throw new IllegalStateException("message.member_category.admin_manual_only");
        }
        member.setCategory(category, lockAutomatic);
        movement(member, MemberMovementType.CAMBIO_CATEGORIA, BigDecimal.ZERO, 0,
                previous, category == null ? null : category.getId(), reason);
        return MemberView.from(member);
    }

    @Transactional(readOnly = true)
    public List<MemberCategoryView> categories() {
        return categories.findByCompanyIdOrderBySortOrderAscMinPointsAscNameAsc(context.currentCompany().getId())
                .stream().map(MemberCategoryView::from).toList();
    }

    @Transactional
    public MemberCategoryView createCategory(MemberCategoryCommand command) {
        return MemberCategoryView.from(categories.save(new MemberCategory(
                context.currentCompany(), command.name(), command.minPoints(),
                command.discountPercent(), command.discountEnabled(), command.sortOrder())));
    }

    @Transactional
    public MemberCategoryView updateCategory(UUID id, MemberCategoryCommand command) {
        var category = category(id);
        category.update(command.name(), command.minPoints(), command.discountPercent(),
                command.discountEnabled(), command.sortOrder());
        return MemberCategoryView.from(category);
    }

    @Transactional
    public void deactivateCategory(UUID id) {
        var category = category(id);
        var fallback = categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(context.currentCompany().getId())
                .stream()
                .filter(value -> !value.getId().equals(id))
                .filter(value -> value.getMinPoints() < category.getMinPoints())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("message.member_category.no_lower_category"));
        category.deactivate();
        members.findByMemberCategoryId(id).forEach(member -> member.setCategory(fallback, false));
    }

    @Transactional
    public MemberSettingsView settings() {
        return MemberSettingsView.from(settings.findById(context.currentCompany().getId())
                .orElseGet(() -> settings.save(new MemberSettings(context.currentCompany()))));
    }

    @Transactional
    public MemberSettingsView updateSettings(MemberSettingsCommand command) {
        requireSmtpIfWelcomeEnabled(command.memberWelcomeEnabled());
        var value = settings.findById(context.currentCompany().getId())
                .orElseGet(() -> settings.save(new MemberSettings(context.currentCompany())));
        value.update(command.balanceAccrualPercent(), command.balanceExpirationPolicy(),
                command.pointsPerEuro(), command.categoryAutoEnabled(),
                command.memberWelcomeEnabled(), command.memberCardCodeFormat(),
                command.welcomeSubjectTemplate(), command.welcomeBodyTemplate());
        return MemberSettingsView.from(value);
    }

    private void requireSmtpIfWelcomeEnabled(boolean welcomeEnabled) {
        if (welcomeEnabled && smtpSettings.findById(context.currentCompany().getId())
                .filter(MemberSmtpSettings::isEnabled)
                .isEmpty()) {
            throw new IllegalStateException("message.member_welcome.smtp_required");
        }
    }

    @Transactional(readOnly = true)
    public List<CommercialChannelView> channels() {
        return channels.findByCompanyIdOrderByCodeAsc(context.currentCompany().getId())
                .stream().map(CommercialChannelView::from).toList();
    }

    @Transactional
    public CommercialChannelView createChannel(CommercialChannelCommand command) {
        return CommercialChannelView.from(channels.save(new CommercialContactChannel(
                context.currentCompany(), command.code(), command.name())));
    }

    @Transactional
    public CommercialChannelView updateChannel(UUID id, CommercialChannelCommand command) {
        var channel = channels.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.commercial_channel.not_found"));
        channel.update(command.code(), command.name(), command.active());
        return CommercialChannelView.from(channel);
    }

    @Transactional(readOnly = true)
    public List<MemberCardDeliveryView> cardDeliveries(MemberCardDeliveryStatus status) {
        var companyId = context.currentCompany().getId();
        var deliveries = status == null
                ? cardDeliveries.findByCompanyId(companyId)
                : cardDeliveries.findByCompanyIdAndStatus(companyId, status);
        return deliveries.stream().map(MemberCardDeliveryView::from).toList();
    }

    @Transactional
    public MemberCardDeliveryView retryCardDelivery(UUID id) {
        var delivery = cardDeliveries.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.member_card_delivery.not_found"));
        delivery.retry();
        return MemberCardDeliveryView.from(delivery);
    }

    private void autoCategory(Member member) {
        var config = settings.findById(context.currentCompany().getId())
                .orElseGet(() -> new MemberSettings(context.currentCompany()));
        if (!config.isCategoryAutoEnabled() || member.isAutoCategoryLocked()) {
            return;
        }
        categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(context.currentCompany().getId()).stream()
                .filter(category -> !category.isManualOnly())
                .filter(category -> member.getMemberPoints() >= category.getMinPoints())
                .findFirst()
                .ifPresent(category -> member.setCategory(category, false));
    }

    private java.util.Optional<MemberCategory> initialCategory() {
        return categories.findByCompanyIdAndActiveTrueOrderByMinPointsAscNameAsc(
                        context.currentCompany().getId())
                .stream()
                .filter(category -> !category.isManualOnly())
                .findFirst();
    }

    private void enqueueWelcomeCard(Member member) {
        var config = settings.findById(context.currentCompany().getId())
                .orElse(null);
        if (config == null) {
            return;
        }
        var email = member.getCustomer().getEmail();
        if (!config.isMemberWelcomeEnabled() || email == null || email.isBlank()) {
            return;
        }
        var subject = config.getWelcomeSubjectTemplate() == null
                ? "Tarjeta de miembro"
                : config.getWelcomeSubjectTemplate();
        var body = config.getWelcomeBodyTemplate() == null
                ? "Codigo de miembro: " + member.getMemberId()
                : config.getWelcomeBodyTemplate().replace("{memberId}", member.getMemberId());
        cardDeliveries.save(new MemberCardDelivery(
                member, email, subject, body, config.getMemberCardCodeFormat(), Instant.now(clock)));
    }
    // Registers the transactional welcome card for the future sender integration.

    private MemberMovementView movement(Member member, MemberMovementType type, BigDecimal balance,
            long points, UUID previousCategoryId, UUID newCategoryId, String reason) {
        return MemberMovementView.from(saveMovement(
                member, null, type, balance, points, previousCategoryId, newCategoryId, reason));
    }

    private MemberMovementView movement(Member member, UUID documentId, MemberMovementType type, BigDecimal balance,
            long points, UUID previousCategoryId, UUID newCategoryId, String reason) {
        return MemberMovementView.from(saveMovement(
                member, documentId, type, balance, points, previousCategoryId, newCategoryId, reason));
    }

    private MemberMovement saveMovement(Member member, UUID documentId, MemberMovementType type, BigDecimal balance,
            long points, UUID previousCategoryId, UUID newCategoryId, String reason) {
        var saved = movements.save(new MemberMovement(
                member, context.currentStore(), context.currentUser(), documentId, type, balance, points,
                previousCategoryId, newCategoryId, reason, Instant.now(clock)));
        syncOutbox.enqueue(new SyncOutboundEventCommand(
                context.currentCompany().getId(),
                context.currentStore().getId(),
                null,
                "MEMBER_MOVEMENT",
                saved.getId(),
                SyncOperation.CREAR,
                Map.of("memberId", member.getId().toString(), "type", type.name())));
        return saved;
    }

    private Instant expiration(BalanceExpirationPolicy policy) {
        var now = Instant.now(clock);
        return switch (policy) {
            case NO_CADUCA -> null;
            case UN_MES -> now.plus(30, ChronoUnit.DAYS);
            case TRES_MESES -> now.plus(90, ChronoUnit.DAYS);
            case SEIS_MESES -> now.plus(180, ChronoUnit.DAYS);
            case UN_ANO -> now.plus(365, ChronoUnit.DAYS);
        };
    }

    private void requireRecentOfficialSync(Member member) {
        var syncedAt = member.getOfficialSyncedAt();
        var limit = Instant.now(clock).minus(5, ChronoUnit.MINUTES);
        if (syncedAt == null || syncedAt.isBefore(limit)) {
            throw new IllegalStateException("message.member.official_sync_required");
        }
    }

    private void consumeLots(Member member, MemberMovement movement, BigDecimal amount) {
        var remaining = amount;
        var orderedLots = lots.findByMemberIdAndAmountRemainingGreaterThan(member.getId(), BigDecimal.ZERO)
                .stream()
                .filter(lot -> lot.getExpiresAt() == null || lot.getExpiresAt().isAfter(Instant.now(clock)))
                .sorted(Comparator
                        .comparing(MemberBalanceLot::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MemberBalanceLot::getCreatedAt))
                .toList();
        for (var lot : orderedLots) {
            if (remaining.signum() <= 0) {
                return;
            }
            var consumed = remaining.min(lot.getAmountRemaining());
            lot.consume(consumed);
            lotConsumptions.save(new MemberBalanceLotConsumption(movement, lot, consumed));
            remaining = remaining.subtract(consumed);
        }
        if (remaining.signum() > 0) {
            throw new IllegalStateException("message.member.balance_lots_insufficient");
        }
    }

    private void saveEligibilitySnapshot(
            CommercialDocument document,
            LoyaltyAccrual accrual) {
        if (accrual.lines().isEmpty()) {
            return;
        }
        var documentLineIds = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(line -> line.getId())
                .collect(Collectors.toSet());
        if (!documentLineIds.containsAll(accrual.lines().keySet())
                || !accrual.lines().keySet().containsAll(documentLineIds)) {
            throw new IllegalArgumentException(
                    "La instantanea de fidelizacion no coincide con las lineas del documento");
        }
        var existing = loyaltyLines.findAllById(documentLineIds).stream()
                .collect(Collectors.toMap(
                        MemberDocumentLoyaltyLine::getDocumentLineId,
                        Function.identity()));
        var pending = new java.util.ArrayList<MemberDocumentLoyaltyLine>();
        accrual.lines().forEach((lineId, expected) -> {
            var current = existing.get(lineId);
            if (current == null) {
                pending.add(new MemberDocumentLoyaltyLine(
                        document.getId(), lineId, expected.eligible(), expected.amount()));
                return;
            }
            if (!current.getDocumentId().equals(document.getId())
                    || current.isEligible() != expected.eligible()
                    || current.getEligibleAmount().compareTo(expected.amount()) != 0) {
                throw new IllegalStateException(
                        "La instantanea historica de la linea de fidelizacion no coincide");
            }
        });
        if (!pending.isEmpty()) {
            loyaltyLines.saveAll(pending);
        }
    }

    private static boolean isSaleForAccrual(CommercialDocument document) {
        return document.getTipo() == CommercialDocumentType.TICKET
                || document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                || document.getTipo() == CommercialDocumentType.ALBARAN_VENTA;
    }

    public record LoyaltyLineEligibility(boolean eligible, BigDecimal amount) {

        public LoyaltyLineEligibility {
            amount = PartyValues.money(amount);
            if (amount.signum() < 0 || (!eligible && amount.signum() != 0)) {
                throw new IllegalArgumentException(
                        "El importe elegible de la linea no es valido");
            }
        }
    }

    public record LoyaltyAccrual(
            BigDecimal documentAmount,
            BigDecimal eligibleDocumentAmount,
            BigDecimal eligiblePaidAmount,
            Map<UUID, LoyaltyLineEligibility> lines) {

        public LoyaltyAccrual {
            documentAmount = PartyValues.money(documentAmount);
            eligibleDocumentAmount = PartyValues.money(eligibleDocumentAmount);
            eligiblePaidAmount = PartyValues.money(eligiblePaidAmount);
            if (documentAmount.signum() < 0
                    || eligibleDocumentAmount.signum() < 0
                    || eligiblePaidAmount.signum() < 0
                    || eligibleDocumentAmount.compareTo(documentAmount) > 0
                    || eligiblePaidAmount.compareTo(eligibleDocumentAmount) > 0) {
                throw new IllegalArgumentException(
                        "La liquidacion de fidelizacion no es valida");
            }
            var canonical = new LinkedHashMap<UUID, LoyaltyLineEligibility>();
            Objects.requireNonNull(lines, "lines").forEach((id, line) ->
                    canonical.put(
                            Objects.requireNonNull(id, "documentLineId"),
                            Objects.requireNonNull(line, "line")));
            lines = Map.copyOf(canonical);
        }
    }

    private Member member(UUID id) {
        return members.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.member.not_found"));
    }

    private MemberCategory category(UUID id) {
        return categories.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.member_category.not_found"));
    }

    public record MemberView(
            UUID id,
            UUID customerId,
            String memberId,
            String numMember,
            BigDecimal balance,
            long points,
            UUID categoryId,
            BigDecimal officialBalance,
            long officialPoints,
            Instant officialSyncedAt,
            boolean autoCategoryLocked,
            boolean active) {

        static MemberView from(Member member) {
            return new MemberView(
                    member.getId(), member.getCustomer().getId(), member.getMemberId(),
                    member.getNumMember(), member.getMemberBalance(), member.getMemberPoints(),
                    member.getMemberCategory() == null ? null : member.getMemberCategory().getId(),
                    member.getOfficialMemberBalance(), member.getOfficialMemberPoints(),
                    member.getOfficialSyncedAt(),
                    member.isAutoCategoryLocked(), member.isActive());
        }
    }

    public record MemberDirectoryView(
            UUID id,
            UUID customerId,
            String memberId,
            String numMember,
            java.time.LocalDate memberSince,
            BigDecimal balance,
            long points,
            UUID categoryId,
            String categoryName,
            boolean active,
            boolean customerActive,
            String clientId,
            String fiscalName,
            DocumentType documentType,
            String documentNumber,
            String phone,
            String email) {

        static MemberDirectoryView from(Member member) {
            var customer = member.getCustomer();
            var category = member.getMemberCategory();
            return new MemberDirectoryView(
                    member.getId(), customer.getId(), member.getMemberId(), member.getNumMember(),
                    member.getMemberSince(), member.getMemberBalance(), member.getMemberPoints(),
                    category == null ? null : category.getId(),
                    category == null ? null : category.getName(),
                    member.isActive(), customer.isActive(), customer.getClientId(),
                    customer.getFiscalName(), customer.getDocumentType(), customer.getDocumentNumber(),
                    customer.getPhone(), customer.getEmail());
        }
    }

    public record OfficialMemberStateCommand(
            UUID sourceEventId,
            UUID memberId,
            BigDecimal balance,
            long points,
            UUID categoryId,
            Instant syncedAt) {
    }

    public record MemberMovementView(
            UUID id,
            MemberMovementType type,
            BigDecimal balanceAmount,
            long pointsAmount,
            String reason,
            Instant createdAt) {

        static MemberMovementView from(MemberMovement movement) {
            return new MemberMovementView(
                    movement.getId(), movement.getType(), movement.getBalanceAmount(),
                    movement.getPointsAmount(), movement.getReason(), movement.getCreatedAt());
        }
    }

    public record MemberCategoryCommand(
            String name,
            long minPoints,
            BigDecimal discountPercent,
            boolean discountEnabled,
            int sortOrder) {
    }

    public record MemberCategoryView(
            UUID id,
            String code,
            String name,
            long minPoints,
            BigDecimal discountPercent,
            boolean discountEnabled,
            boolean manualOnly,
            boolean active,
            int sortOrder) {

        static MemberCategoryView from(MemberCategory category) {
            return new MemberCategoryView(
                    category.getId(), category.getCode(), category.getName(), category.getMinPoints(),
                    category.getDiscountPercent(), category.isDiscountEnabled(),
                    category.isManualOnly(), category.isActive(), category.getSortOrder());
        }
    }

    public record MemberSettingsCommand(
            BigDecimal balanceAccrualPercent,
            BalanceExpirationPolicy balanceExpirationPolicy,
            BigDecimal pointsPerEuro,
            boolean categoryAutoEnabled,
            boolean memberWelcomeEnabled,
            MemberCardCodeFormat memberCardCodeFormat,
            String welcomeSubjectTemplate,
            String welcomeBodyTemplate) {
    }

    public record MemberSettingsView(
            BigDecimal balanceAccrualPercent,
            BalanceExpirationPolicy balanceExpirationPolicy,
            BigDecimal pointsPerEuro,
            boolean categoryAutoEnabled,
            boolean memberWelcomeEnabled,
            MemberCardCodeFormat memberCardCodeFormat,
            String welcomeSubjectTemplate,
            String welcomeBodyTemplate) {

        static MemberSettingsView from(MemberSettings settings) {
            return new MemberSettingsView(
                    settings.getBalanceAccrualPercent(), settings.getBalanceExpirationPolicy(),
                    settings.getPointsPerEuro(), settings.isCategoryAutoEnabled(),
                    settings.isMemberWelcomeEnabled(), settings.getMemberCardCodeFormat(),
                    settings.getWelcomeSubjectTemplate(), settings.getWelcomeBodyTemplate());
        }
    }

    public record CommercialChannelCommand(String code, String name, boolean active) {
    }

    public record CommercialChannelView(UUID id, String code, String name, boolean active) {

        static CommercialChannelView from(CommercialContactChannel channel) {
            return new CommercialChannelView(
                    channel.getId(), channel.getCode(), channel.getName(), channel.isActive());
        }
    }

    public record MemberCardDeliveryView(
            UUID id,
            UUID memberId,
            String email,
            String subject,
            String body,
            MemberCardCodeFormat cardCodeFormat,
            String cardCode,
            MemberCardDeliveryStatus status,
            Instant createdAt,
            Instant sentAt,
            String errorMessage) {

        static MemberCardDeliveryView from(MemberCardDelivery delivery) {
            return new MemberCardDeliveryView(
                    delivery.getId(), delivery.getMember().getId(), delivery.getEmail(),
                    delivery.getSubject(), delivery.getBody(), delivery.getCardCodeFormat(),
                    delivery.getCardCode(), delivery.getStatus(), delivery.getCreatedAt(),
                    delivery.getSentAt(), delivery.getErrorMessage());
        }
    }
}
