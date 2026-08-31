package com.tpverp.backend.party;

import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservationRepository;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservation;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservationStatus;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.ManualPointsAdjustmentRequest;
import com.tpverp.backend.party.loyalty.sync.MemberPointsSyncPublisher;
import com.tpverp.backend.party.loyalty.sync.MemberWalletSyncPublisher;
import com.tpverp.backend.party.loyalty.sync.MemberReturnBalanceRecoveryCommand;
import com.tpverp.backend.party.loyalty.sync.MemberReturnBalanceRecoveryOutboxPublisher;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionCoordinator;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionCoordinator.ProjectionDecision;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberLoyaltyService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.tpverp.backend.party.loyalty.category.MemberCategoryAuthorityGuard categoryAuthority;

    private static final Set<LocalMemberBalanceReservationStatus>
            EXPIRY_BLOCKING_RESERVATION_STATUSES = Set.of(
                    LocalMemberBalanceReservationStatus.ACTIVE,
                    LocalMemberBalanceReservationStatus.PREPARED,
                    LocalMemberBalanceReservationStatus.TICKET_COMMITTED,
                    LocalMemberBalanceReservationStatus.FINALIZE_PENDING);

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
    private final CommercialDocumentRepository documents;
    private final SyncOutboxService syncOutbox;
    private final MemberWalletSyncPublisher walletSyncPublisher;
    private final MemberPointsSyncPublisher pointsSyncPublisher;
    private final MemberReturnBalanceRecoveryOutboxPublisher returnBalanceRecoveryPublisher;
    private final MemberPointsProjectionCoordinator pointsProjectionCoordinator;
    private final MemberBalanceCentralGateway pointsCentralGateway;
    private final MemberBalanceCentralContextResolver centralContextResolver;
    private final PartyContext context;
    private final Clock clock;
    private com.tpverp.backend.audit.AuditService audit;
    private LocalMemberBalanceReservationRepository localBalanceReservations;
    private MemberReturnBalanceRetentionPlanner retentionPlanner;

    @org.springframework.beans.factory.annotation.Autowired
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
            CommercialDocumentRepository documents,
            SyncOutboxService syncOutbox,
            MemberWalletSyncPublisher walletSyncPublisher,
            MemberPointsSyncPublisher pointsSyncPublisher,
            MemberReturnBalanceRecoveryOutboxPublisher returnBalanceRecoveryPublisher,
            MemberPointsProjectionCoordinator pointsProjectionCoordinator,
            MemberBalanceCentralGateway pointsCentralGateway,
            MemberBalanceCentralContextResolver centralContextResolver,
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
        this.documents = documents;
        this.syncOutbox = syncOutbox;
        this.walletSyncPublisher = walletSyncPublisher;
        this.pointsSyncPublisher = pointsSyncPublisher;
        this.returnBalanceRecoveryPublisher = returnBalanceRecoveryPublisher;
        this.pointsProjectionCoordinator = pointsProjectionCoordinator;
        this.pointsCentralGateway = pointsCentralGateway;
        this.centralContextResolver = centralContextResolver;
        this.context = context;
        this.clock = clock;
    }

    MemberLoyaltyService(
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
            CommercialDocumentRepository documents,
            SyncOutboxService syncOutbox,
            PartyContext context,
            Clock clock) {
        this(
                members,
                categories,
                settings,
                movements,
                lots,
                lotConsumptions,
                loyaltySettlements,
                loyaltyLines,
                cardDeliveries,
                smtpSettings,
                channels,
                documents,
                syncOutbox,
                new MemberWalletSyncPublisher(syncOutbox, context),
                null,
                new MemberReturnBalanceRecoveryOutboxPublisher(syncOutbox),
                null,
                null,
                null,
                context,
                clock);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAuditService(com.tpverp.backend.audit.AuditService audit) {
        this.audit = audit;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLocalBalanceReservations(
            LocalMemberBalanceReservationRepository localBalanceReservations) {
        this.localBalanceReservations = localBalanceReservations;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRetentionPlanner(MemberReturnBalanceRetentionPlanner retentionPlanner) {
        this.retentionPlanner = retentionPlanner;
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

    @Transactional(readOnly = true)
    public MemberWalletView wallet(UUID customerId) {
        var member = members.findByCustomerIdAndCompanyId(
                        customerId, context.currentCompany().getId())
                .filter(Member::isActive)
                .orElseThrow(() -> new IllegalArgumentException("message.member.not_found"));
        var now = Instant.now(clock);
        var expiryBlockStartedAt = expirationBlockStartedAt(member.getId(), now);
        var availableLots = lots.findByMemberIdAndAmountRemainingGreaterThan(
                        member.getId(), BigDecimal.ZERO).stream()
                .filter(lot -> isLotAvailableAt(lot, now, expiryBlockStartedAt))
                .sorted(Comparator
                        .comparingInt((MemberBalanceLot lot) ->
                                lot.getBalanceType() == MemberBalanceLotType.LOYALTY ? 0 : 1)
                        .thenComparing(MemberBalanceLot::getExpiresAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MemberBalanceLot::getCreatedAt)
                        .thenComparing(MemberBalanceLot::getId))
                .toList();
        var loyalty = availableForType(
                availableLots, MemberBalanceLotType.LOYALTY, member.getMemberBalance());
        var returnCredit = availableForType(
                availableLots, MemberBalanceLotType.RETURN_CREDIT,
                member.getReturnCreditBalance());
        var documentIds = availableLots.stream()
                .map(MemberBalanceLot::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var documentNumbers = documentIds.isEmpty()
                ? Map.<UUID, String>of()
                : documents.findDocumentNumbersByIdsAndCompanyId(
                                documentIds, context.currentCompany().getId()).stream()
                        .filter(document -> document.getDocumentId() != null
                                && normalizeDocumentNumber(document.getDocumentNumber()) != null)
                        .collect(Collectors.toMap(
                                CommercialDocumentRepository.DocumentNumberProjection::getDocumentId,
                                document -> normalizeDocumentNumber(document.getDocumentNumber()),
                                (first, ignored) -> first,
                                LinkedHashMap::new));
        return new MemberWalletView(
                loyalty, returnCredit, PartyValues.money(loyalty.add(returnCredit)),
                availableLots.stream()
                        .map(lot -> MemberBalanceLotView.from(
                                lot, documentNumbers.get(lot.getDocumentId())))
                        .toList());
    }

    /**
     * Checks membership status without reading or requiring any wallet balance.
     * Return-credit creation is only valid for an active member, including when
     * the member has no existing balance or lots.
     */
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID customerId) {
        return customerId != null
                && members.findByCustomerIdAndCompanyId(
                                customerId, context.currentCompany().getId())
                        .map(Member::isActive)
                        .orElse(false);
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
        var pointsBefore = member.getMemberPoints();
        var debtBefore = member.getLoyaltyPointsDebt();
        var occurredAt = Instant.now(clock);
        var projection = points == 0 ? null : allocatePointsProjection();
        if (projection != null
                && projection.status() == MemberPointsProjectionStatus.CENTRAL_ACTIVE) {
            if (pointsCentralGateway == null || centralContextResolver == null
                    || pointsSyncPublisher == null) {
                throw new IllegalStateException(
                        "El ajuste central de puntos no esta configurado");
            }
            UUID operationId = UUID.randomUUID();
            var operation = new MemberPointsOperation(
                    operationId,
                    member,
                    context.currentCompany().getId(),
                    context.currentStore().getId(),
                    projection.storeSequence(),
                    MemberPointsOperationType.MANUAL_ADJUSTMENT,
                    points,
                    null,
                    null,
                    occurredAt,
                    0,
                    0,
                    null);
            pointsSyncPublisher.publishCreated(operation);
            var central = centralContextResolver.resolve(context.currentStore().getId());
            var official = pointsCentralGateway.adjustPoints(
                    new ManualPointsAdjustmentRequest(
                            central.companyId(),
                            central.storeId(),
                            operationId,
                            member.getId(),
                            projection.storeSequence(),
                            points,
                            occurredAt));
            member.applyOfficialPoints(
                    official.points().longValueExact(),
                    official.pointsDebt().longValueExact(),
                    official.syncedAt());
            autoCategory(member);
            return movement(
                    member,
                    MemberMovementType.AJUSTE_MANUAL_PUNTOS,
                    BigDecimal.ZERO,
                    Math.subtractExact(member.getMemberPoints(), pointsBefore),
                    null,
                    null,
                    reason);
        }
        if (projection != null && !projection.projectLocally()) {
            throw new IllegalStateException(
                    "Los ajustes manuales de puntos están temporalmente bloqueados durante la sincronización central.");
        }
        member.applyPoints(points);
        autoCategory(member);
        var result = movement(member, MemberMovementType.AJUSTE_MANUAL_PUNTOS,
                BigDecimal.ZERO, points, null, null, reason);
        if (points != 0) {
            publishPointsOperation(
                    UUID.randomUUID(),
                    member,
                    MemberPointsOperationType.MANUAL_ADJUSTMENT,
                    points,
                    null,
                    null,
                    occurredAt,
                    pointsBefore,
                    debtBefore,
                    null,
                    projection);
        }
        return result;
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
        var pointsBefore = member.getMemberPoints();
        var debtBefore = member.getLoyaltyPointsDebt();
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
        var previousEligiblePaidAmount = settlement.getEligiblePaidAmount();
        var paid = PartyValues.money(
                accrual.eligiblePaidAmount().subtract(
                        previousEligiblePaidAmount));
        if (paid.signum() < 0) {
            throw new IllegalStateException(
                    "El importe elegible cobrado acumulado no puede disminuir");
        }
        if (paid.signum() <= 0) {
            loyaltySettlements.save(settlement);
            return;
        }
        var points = config.isPointsAccrualEnabled()
                ? paid.multiply(config.getPointsPerEuro())
                        .divide(config.getPointsAccrualBaseAmount(), 0, RoundingMode.FLOOR)
                        .longValue()
                : 0L;
        var balance = config.isBalanceAccrualEnabled()
                ? paid.multiply(config.getBalanceAccrualBaseAmount()
                                .multiply(config.getBalanceAccrualPercent())
                                .movePointLeft(2))
                        .divide(config.getBalanceAccrualBaseAmount(), 2, RoundingMode.DOWN)
                : BigDecimal.ZERO.setScale(2);
        var sourceCheckpoint = PartyValues.money(
                accrual.eligiblePaidAmount()).toPlainString();
        var pointsProjection = allocatePointsProjection();
        long repaidPoints = 0;
        long availablePoints = 0;
        long deferredPoints = 0;
        if (points > 0 && pointsProjection.projectLocally()) {
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
        } else if (points > 0) {
            deferredPoints = points;
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
                var lot = lots.save(new MemberBalanceLot(
                        member,
                        movement,
                        MemberBalanceLotType.LOYALTY,
                        availableBalance,
                        Instant.now(clock),
                        expiration(config.getBalanceExpirationPolicy())));
                walletSyncPublisher.publishCreated(lot);
            }
        }
        settlement.recordAccrual(
                paid,
                points,
                availablePoints,
                repaidPoints,
                deferredPoints,
                balance,
                availableBalance,
                repaidBalance,
                now);
        loyaltySettlements.save(settlement);
        publishPointsOperation(
                saleEarnOperationId(
                        document.getId(),
                        previousEligiblePaidAmount,
                        sourceCheckpoint),
                member,
                MemberPointsOperationType.SALE_EARN,
                points,
                document.getId(),
                null,
                now,
                pointsBefore,
                debtBefore,
                sourceCheckpoint,
                pointsProjection);
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
        return consumeBalanceForSaleReduction(document, amount);
    }

    @Transactional
    public BigDecimal consumeBalanceForSaleReduction(
            CommercialDocument document,
            BigDecimal amount) {
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
        var now = Instant.now(clock);
        var expiryBlockStartedAt = expirationBlockStartedAt(member.getId(), now);
        requireAvailableBalance(
                member, MemberBalanceLotType.LOYALTY, member.getMemberBalance(), value,
                now, expiryBlockStartedAt);
        member.applyBalance(value.negate());
        var movement = saveMovement(member, document.getId(), MemberMovementType.USO_SALDO,
                value.negate(), 0, null, null, "saldo del miembro aplicado a venta");
        consumeLots(
                member, movement, MemberBalanceLotType.LOYALTY, value,
                now, expiryBlockStartedAt);
        return value;
    }
    // Consumes member balance against FIFO earned lots after checking the authoritative snapshot.

    @Transactional(readOnly = true)
    public BigDecimal validateBalanceForCheckout(UUID customerId, BigDecimal amount) {
        if (customerId == null) {
            throw new IllegalArgumentException(
                    "message.member.customer_required_for_balance_payment");
        }
        var member = members.findByCustomerIdAndCompanyId(
                customerId, context.currentCompany().getId())
                .filter(Member::isActive)
                .orElseThrow(() -> new IllegalArgumentException("message.member.not_found"));
        requireRecentOfficialSync(member);
        var value = PartyValues.money(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "message.member.balance_payment_positive_required");
        }
        var now = Instant.now(clock);
        var expiryBlockStartedAt = expirationBlockStartedAt(member.getId(), now);
        return requireAvailableBalance(
                member, MemberBalanceLotType.LOYALTY, member.getMemberBalance(), value,
                now, expiryBlockStartedAt);
    }

    @Transactional
    public BigDecimal creditReturnBalance(
            UUID customerId,
            UUID documentId,
            BigDecimal amount,
            Instant expiresAt) {
        var member = members.findByCustomerIdAndCompanyId(
                        customerId, context.currentCompany().getId())
                .filter(Member::isActive)
                .orElseThrow(() -> new IllegalArgumentException("message.member.not_found"));
        var value = PartyValues.money(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "El abono de devolucion debe ser positivo");
        }
        var existing = movements.findByDocumentIdOrderByCreatedAtAsc(documentId).stream()
                .filter(movement -> movement.getType()
                        == MemberMovementType.ABONO_CREDITO_DEVOLUCION)
                .findFirst();
        if (existing.isPresent()) {
            if (existing.get().getBalanceAmount().compareTo(value) != 0) {
                throw new IllegalStateException(
                        "La devolucion ya tiene otro abono de saldo a favor");
            }
            return value;
        }
        member.applyReturnCredit(value);
        var movement = saveMovement(
                member, documentId, MemberMovementType.ABONO_CREDITO_DEVOLUCION,
                value, 0, null, null, "abono de devolucion a saldo a favor");
        var lot = lots.save(new MemberBalanceLot(
                member, movement, MemberBalanceLotType.RETURN_CREDIT,
                value, Instant.now(clock), expiresAt));
        walletSyncPublisher.publishCreated(lot);
        return value;
    }

    @Transactional
    public BigDecimal restoreReturnCreditAfterTicketCancellation(
            CommercialDocument document,
            BigDecimal amount,
            Instant expiresAt) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("ticket_required_for_return_credit_restoration");
        }
        var value = PartyValues.money(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "El saldo a favor restaurado debe ser positivo");
        }
        var documentMovements = movements.findByDocumentIdOrderByCreatedAtAsc(
                document.getId());
        var originalConsumptions = documentMovements.stream()
                .filter(movement -> movement.getType()
                        == MemberMovementType.USO_CREDITO_DEVOLUCION)
                .toList();
        if (originalConsumptions.isEmpty()) {
            throw new IllegalStateException(
                    "no existe consumo de saldo a favor para restaurar");
        }
        var member = originalConsumptions.getFirst().getMember();
        if (originalConsumptions.stream().anyMatch(movement ->
                !movement.getMember().getId().equals(member.getId()))) {
            throw new IllegalStateException(
                    "el ticket contiene saldo a favor de varios miembros");
        }
        var consumed = PartyValues.money(originalConsumptions.stream()
                .map(MemberMovement::getBalanceAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (consumed.compareTo(value) != 0) {
            throw new IllegalStateException(
                    "el pago con saldo a favor no coincide con su consumo");
        }
        var existingRestorations = documentMovements.stream()
                .filter(movement -> movement.getType()
                        == MemberMovementType.ABONO_CREDITO_DEVOLUCION)
                .toList();
        if (existingRestorations.size() > 1) {
            throw new IllegalStateException(
                    "el ticket tiene varias restauraciones de saldo a favor");
        }
        if (!existingRestorations.isEmpty()) {
            var existing = existingRestorations.getFirst();
            if (!existing.getMember().getId().equals(member.getId())
                    || existing.getBalanceAmount().compareTo(value) != 0) {
                throw new IllegalStateException(
                        "la restauracion existente no coincide con el ticket");
            }
            return value;
        }
        member.applyReturnCredit(value);
        var restoredAt = Instant.now(clock);
        var restoration = saveMovement(
                member,
                document.getId(),
                MemberMovementType.ABONO_CREDITO_DEVOLUCION,
                value,
                0,
                null,
                null,
                "restauracion por anulacion del ticket " + document.getNumero());
        var lot = lots.save(new MemberBalanceLot(
                member,
                restoration,
                MemberBalanceLotType.RETURN_CREDIT,
                value,
                restoredAt,
                expiresAt));
        walletSyncPublisher.publishCreated(lot);
        return value;
    }

    @Transactional
    public BigDecimal consumeReturnCreditForPayment(
            CommercialDocument document,
            BigDecimal amount) {
        if (document.getClienteId() == null) {
            throw new IllegalArgumentException(
                    "message.member.customer_required_for_balance_payment");
        }
        var member = members.findByCustomerIdAndCompanyId(
                        document.getClienteId(), context.currentCompany().getId())
                .filter(Member::isActive)
                .orElseThrow(() -> new IllegalArgumentException("message.member.not_found"));
        requireRecentOfficialSync(member);
        var value = PartyValues.money(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "El uso de saldo a favor debe ser positivo");
        }
        var now = Instant.now(clock);
        var expiryBlockStartedAt = expirationBlockStartedAt(member.getId(), now);
        requireAvailableBalance(
                member, MemberBalanceLotType.RETURN_CREDIT,
                member.getReturnCreditBalance(), value, now, expiryBlockStartedAt);
        member.applyReturnCredit(value.negate());
        var movement = saveMovement(
                member, document.getId(), MemberMovementType.USO_CREDITO_DEVOLUCION,
                value.negate(), 0, null, null, "saldo a favor aplicado al cobro");
        consumeLots(
                member, movement, MemberBalanceLotType.RETURN_CREDIT, value,
                now, expiryBlockStartedAt);
        return value;
    }

    @Transactional
    public void reverseConfirmedReturn(
            CommercialDocument original,
            CommercialDocument returnDocument,
            BigDecimal cumulativeRefund,
            BigDecimal cumulativeEligibleRefund) {
        reverseConfirmedReturn(original, returnDocument, cumulativeRefund,
                cumulativeEligibleRefund,
                returnDocument.getReturnRequestId() == null
                        ? returnDocument.getId() : returnDocument.getReturnRequestId());
    }

    @Transactional
    public void reverseConfirmedReturn(
            CommercialDocument original,
            CommercialDocument returnDocument,
            BigDecimal cumulativeRefund,
            BigDecimal cumulativeEligibleRefund,
            UUID operationId) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(returnDocument, "returnDocument");
        if (original.getId().equals(returnDocument.getId())) {
            throw new IllegalArgumentException(
                    "La devolucion no puede ser su propio documento de origen");
        }
        var recoveryReservation = retentionReservation(returnDocument, operationId);
        var settlement = loyaltySettlements.findById(original.getId()).orElse(null);
        if (settlement == null) {
            if (recoveryReservation != null) {
                var member = members.findById(recoveryReservation.getMemberId())
                        .orElseThrow(() -> new IllegalStateException(
                                "La reserva de retencion referencia un miembro inexistente"));
                if (!member.getCompany().getId().equals(context.currentCompany().getId())) {
                    throw new IllegalStateException("La reserva de retencion no pertenece a la empresa");
                }
                enqueueReturnBalanceRecovery(operationId, original, returnDocument,
                        member, MemberReturnBalanceRetentionPlanner.Plan.none(original.getId()));
            }
            return;
        }
        var now = Instant.now(clock);
        var plan = settlement.planReversal(
                cumulativeRefund, cumulativeEligibleRefund);
        MemberReturnBalanceRetentionPlanner.Plan retention = retentionPlanner == null
                ? MemberReturnBalanceRetentionPlanner.Plan.none(original.getId())
                : retentionPlanner.plan(original, cumulativeRefund, cumulativeEligibleRefund);
        var member = settlement.getMember();
        validatePreparedRetentionSnapshot(returnDocument, operationId, member.getId(), retention);
        var pointsBefore = member.getMemberPoints();
        var debtBefore = member.getLoyaltyPointsDebt();
        long pointsDebtCreated = 0;
        var balanceDebtCreated = BigDecimal.ZERO.setScale(2);

        var reversedPointsNow = Math.addExact(
                Math.addExact(plan.grantedPointsDelta(), plan.debtPointsDelta()),
                plan.deferredPointsDelta());
        var pointsProjection = reversedPointsNow > 0
                ? allocatePointsProjection()
                : null;
        if (reversedPointsNow > 0 && pointsProjection.projectLocally()) {
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

        if (reversedPointsNow > 0 && pointsProjection.projectLocally()) {
            autoCategory(member);
        }
        settlement.recordReversal(
                plan,
                pointsDebtCreated,
                balanceDebtCreated,
                reversedPointsNow > 0 && !pointsProjection.projectLocally()
                        ? reversedPointsNow : 0,
                now);
        loyaltySettlements.save(settlement);
        if (reversedPointsNow > 0) {
            publishPointsOperation(
                    returnDocument.getId(),
                    member,
                    MemberPointsOperationType.RETURN_REVERSAL,
                    reversedPointsNow,
                    returnDocument.getId(),
                    original.getId(),
                    now,
                    pointsBefore,
                    debtBefore,
                    null,
                    pointsProjection);
        }
        if (retention != null && (retention.attributedAmount().signum() > 0
                && !retention.claims().isEmpty()
                || recoveryReservation != null)) {
            enqueueReturnBalanceRecovery(operationId, original, returnDocument,
                    settlement.getMember(), retention);
        }
    }

    private LocalMemberBalanceReservation retentionReservation(
            CommercialDocument returnDocument, UUID operationId) {
        if (localBalanceReservations == null || returnDocument.getTerminalOrigenId() == null) {
            return null;
        }
        return localBalanceReservations
                .findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                        returnDocument.getTiendaId(), returnDocument.getTerminalOrigenId(),
                        operationId.toString())
                .orElse(null);
    }

    /**
     * DocumentService locks the source document before invoking the loyalty
     * reversal. Compare the final server-side amount with the durable
     * checkout capacity before creating reversal movements or the recovery
     * outbox. Lot/fingerprint shifts are reconciled by the central projector;
     * only an amount that no longer fits the prepared F10 is rejected.
     */
    private void validatePreparedRetentionSnapshot(
            CommercialDocument returnDocument,
            UUID operationId,
            UUID expectedMemberId,
            MemberReturnBalanceRetentionPlanner.Plan retention) {
        if (retention == null || localBalanceReservations == null
                || returnDocument.getTerminalOrigenId() == null) {
            return;
        }
        var reservation = localBalanceReservations
                .findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                        returnDocument.getTiendaId(), returnDocument.getTerminalOrigenId(),
                        operationId.toString())
                .orElse(null);
        if (reservation == null || !expectedMemberId.equals(reservation.getMemberId())
                || reservation.getPreparedLoyaltyAmount().signum() <= 0) {
            return;
        }
        BigDecimal knownHeld = retention.claims().stream()
                .map(claim -> reservation.retentionSnapshotKnownAmount(
                        claim.lotId(), claim.amount()))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (reservation.getPreparedLoyaltyAmount().add(knownHeld)
                .compareTo(reservation.getReservedLoyaltyAmount()) > 0) {
            throw new IllegalStateException("member_wallet_retention_capacity_conflict");
        }
    }

    private void enqueueReturnBalanceRecovery(
            UUID operationId,
            CommercialDocument original,
            CommercialDocument returnDocument,
            Member member,
            MemberReturnBalanceRetentionPlanner.Plan plan) {
        if (localBalanceReservations != null && returnDocument.getTerminalOrigenId() != null) {
            var reservation = localBalanceReservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                            returnDocument.getTiendaId(), returnDocument.getTerminalOrigenId(), operationId.toString())
                    .filter(value -> value.getMemberId().equals(member.getId()))
                    .orElse(null);
            if (reservation != null) {
                publishReturnBalanceRecovery(operationId, original, returnDocument, member, plan,
                        new MemberReturnBalanceRecoveryCommand.ReservationIdentity(
                                reservation.getCentralReservationId(), reservation.getSaleId()));
                return;
            }
        }
        publishReturnBalanceRecovery(operationId, original, returnDocument, member, plan, null);
    }

    private void publishReturnBalanceRecovery(
            UUID operationId,
            CommercialDocument original,
            CommercialDocument returnDocument,
            Member member,
            MemberReturnBalanceRetentionPlanner.Plan plan,
            MemberReturnBalanceRecoveryCommand.ReservationIdentity reservation) {
        returnBalanceRecoveryPublisher.publish(new MemberReturnBalanceRecoveryCommand(
                operationId,
                member.getCompany().getId(),
                returnDocument.getTiendaId(),
                returnDocument.getTerminalOrigenId(),
                member.getId(),
                original.getId(),
                returnDocument.getId(),
                plan.attributedAmount(),
                plan.fingerprint(),
                plan.claims(),
                reservation));
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
                        .comparing(
                                (MemberBalanceLotConsumption value) ->
                                        value.getLot().getExpiresAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(value -> value.getLot().getCreatedAt())
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
        var settlement = loyaltySettlements.findById(document.getId()).orElse(null);
        var originals = all.stream()
                .filter(movement -> movement.getType() == MemberMovementType.ACUMULACION_PUNTOS
                        || movement.getType() == MemberMovementType.ACUMULACION_SALDO
                        || movement.getType() == MemberMovementType.USO_SALDO)
                .toList();
        if (originals.isEmpty() && settlement == null) {
            return;
        }
        var member = settlement == null
                ? originals.getFirst().getMember()
                : settlement.getMember();
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
            var locallyGranted = originals.stream()
                    .filter(movement ->
                            movement.getType() == MemberMovementType.ACUMULACION_PUNTOS)
                    .mapToLong(MemberMovement::getPointsAmount)
                    .sum();
            var authoritativeAmount = settlement == null
                    ? locallyGranted
                    : settlement.getGeneratedPoints();
            if (authoritativeAmount > 0) {
                var pointsBefore = member.getMemberPoints();
                var debtBefore = member.getLoyaltyPointsDebt();
                var occurredAt = Instant.now(clock);
                var projection = allocatePointsProjection();
                if (projection.projectLocally() && locallyGranted > 0) {
                    member.applyPoints(-locallyGranted);
                    autoCategory(member);
                    movement(member, document.getId(),
                            MemberMovementType.ANULACION_ACUMULACION_PUNTOS,
                            BigDecimal.ZERO, -locallyGranted, null, null, reason);
                } else if (!projection.projectLocally() && settlement != null) {
                    settlement.deferCancellation(occurredAt);
                }
                publishPointsOperation(
                        UUID.nameUUIDFromBytes((
                                "MEMBER_POINTS_OPERATION|SALE_CANCELLATION|"
                                        + document.getId())
                                .getBytes(StandardCharsets.UTF_8)),
                        member,
                        MemberPointsOperationType.SALE_CANCELLATION,
                        authoritativeAmount,
                        null,
                        document.getId(),
                        occurredAt,
                        pointsBefore,
                        debtBefore,
                        null,
                        projection);
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
        Map<UUID, Optional<Instant>> expiryBlocks = new LinkedHashMap<>();
        for (var lot : lots.findByExpiresAtBeforeAndExpiredAtIsNullAndAmountRemainingGreaterThan(
                now, BigDecimal.ZERO)) {
            var expiryBlockStartedAt = expiryBlocks.computeIfAbsent(
                    lot.getMember().getId(),
                    memberId -> expirationBlockStartedAt(memberId, now));
            if (isExpirationProtected(lot, expiryBlockStartedAt)) {
                continue;
            }
            var amount = lot.expire(now);
            if (amount.signum() <= 0) {
                continue;
            }
            if (lot.getBalanceType() == MemberBalanceLotType.RETURN_CREDIT) {
                lot.getMember().expireReturnCredit(amount);
                movement(lot.getMember(), MemberMovementType.CADUCIDAD_CREDITO_DEVOLUCION,
                        amount.negate(), 0, null, null,
                        "caducidad saldo a favor por devolucion");
            } else {
                lot.getMember().expireBalance(amount);
                movement(lot.getMember(), MemberMovementType.CADUCIDAD_SALDO,
                        amount.negate(), 0, null, null, "caducidad saldo");
            }
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
        categoryAuthority.requireLocalMutation();
        var member = member(memberId);
        UUID previous = member.getMemberCategory() == null ? null : member.getMemberCategory().getId();
        var category = categoryId == null ? null : category(categoryId);
        if (category != null && category.isManualOnly() && !context.currentUser().isProtegido()) {
            throw new IllegalStateException("message.member_category.admin_manual_only");
        }
        member.setCategory(category, lockAutomatic);
        categoryMovement(
                member,
                previous,
                category == null ? null : category.getId(),
                lockAutomatic,
                reason);
        return MemberView.from(member);
    }

    @Transactional(readOnly = true)
    public List<MemberCategoryView> categories() {
        return categories.findByCompanyIdOrderBySortOrderAscMinPointsAscNameAsc(context.currentCompany().getId())
                .stream().map(MemberCategoryView::from).toList();
    }

    @Transactional
    public MemberCategoryView createCategory(MemberCategoryCommand command) {
        categoryAuthority.requireLocalMutation();
        ensureUniqueAutomaticThreshold(null, command.minPoints(), command.manualOnly(), true);
        var category = categories.save(new MemberCategory(
                context.currentCompany(), command.name(), command.minPoints(),
                command.discountPercent(), command.discountEnabled(), command.manualOnly(), command.sortOrder()));
        recalculateAutomaticCategories();
        auditCategory("MEMBER_CATEGORY_CREATED", category);
        return MemberCategoryView.from(category);
    }

    @Transactional
    public MemberCategoryView updateCategory(UUID id, MemberCategoryCommand command) {
        categoryAuthority.requireLocalMutation();
        var category = category(id);
        ensureUniqueAutomaticThreshold(id, command.minPoints(), command.manualOnly(), category.isActive());
        category.update(command.name(), command.minPoints(), command.discountPercent(),
                command.discountEnabled(), command.manualOnly(), command.sortOrder());
        recalculateAutomaticCategories();
        auditCategory("MEMBER_CATEGORY_UPDATED", category);
        return MemberCategoryView.from(category);
    }

    @Transactional
    public void deactivateCategory(UUID id) {
        categoryAuthority.requireLocalMutation();
        var category = category(id);
        if (!category.isActive()) {
            return;
        }
        var assigned = members.findByMemberCategoryId(id);
        MemberCategory fallback = null;
        if (!assigned.isEmpty()) {
            fallback = categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(context.currentCompany().getId())
                    .stream()
                    .filter(value -> !value.getId().equals(id))
                    .filter(value -> !value.isManualOnly())
                    .filter(value -> value.getMinPoints() < category.getMinPoints())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("message.member_category.no_lower_category"));
        }
        category.deactivate();
        if (fallback != null) {
            var target = fallback;
            assigned.forEach(member -> member.setCategory(target, false));
        }
        recalculateAutomaticCategories();
        auditCategory("MEMBER_CATEGORY_DEACTIVATED", category);
    }

    @Transactional
    public MemberCategoryView activateCategory(UUID id) {
        categoryAuthority.requireLocalMutation();
        var category = category(id);
        if (!category.isActive()) {
            ensureUniqueAutomaticThreshold(id, category.getMinPoints(), category.isManualOnly(), true);
            category.activate();
            recalculateAutomaticCategories();
            auditCategory("MEMBER_CATEGORY_ACTIVATED", category);
        }
        return MemberCategoryView.from(category);
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
        value.update(command.balanceAccrualEnabled(), command.balanceAccrualBaseAmount(),
                command.balanceAccrualPercent(), command.balanceExpirationPolicy(),
                command.pointsAccrualEnabled(), command.pointsAccrualBaseAmount(),
                command.pointsPerEuro(), command.categoryAutoEnabled(),
                command.memberWelcomeEnabled(), command.memberCardCodeFormat(),
                command.welcomeSubjectTemplate(), command.welcomeBodyTemplate());
        if (value.isCategoryAutoEnabled()) {
            recalculateAutomaticCategories();
        }
        if (audit != null) {
            var details = new LinkedHashMap<String, Object>();
            details.put("companyId", value.getCompanyId());
            details.put("pointsAccrualEnabled", value.isPointsAccrualEnabled());
            details.put("pointsAccrualBaseAmount", value.getPointsAccrualBaseAmount());
            details.put("pointsPerEuro", value.getPointsPerEuro());
            details.put("balanceAccrualEnabled", value.isBalanceAccrualEnabled());
            details.put("balanceAccrualBaseAmount", value.getBalanceAccrualBaseAmount());
            details.put("balanceAccrualPercent", value.getBalanceAccrualPercent());
            details.put("balanceExpirationPolicy", value.getBalanceExpirationPolicy().name());
            details.put("categoryAutoEnabled", value.isCategoryAutoEnabled());
            audit.record("MEMBER_LOYALTY_SETTINGS_UPDATED",
                    com.tpverp.backend.audit.AuditResult.EXITO, Map.copyOf(details));
        }
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
    public List<MemberCardDeliveryView> cardDeliveries(MemberCardDeliveryStatus status, UUID memberId) {
        var companyId = context.currentCompany().getId();
        var deliveries = memberId == null
                ? status == null
                    ? cardDeliveries.findByCompanyId(companyId)
                    : cardDeliveries.findByCompanyIdAndStatus(companyId, status)
                : status == null
                    ? cardDeliveries.findByCompanyIdAndMemberId(companyId, memberId)
                    : cardDeliveries.findByCompanyIdAndMemberIdAndStatus(companyId, memberId, status);
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
        if (!config.isCategoryAutoEnabled() || member.isAutoCategoryLocked()
                || (member.getMemberCategory() != null && member.getMemberCategory().isManualOnly())) {
            return;
        }
        var selected = categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(context.currentCompany().getId()).stream()
                .filter(category -> !category.isManualOnly())
                .filter(category -> member.getMemberPoints() >= category.getMinPoints())
                .findFirst();
        member.setCategory(selected.orElse(null), false);
    }

    private void recalculateAutomaticCategories() {
        var config = settings.findById(context.currentCompany().getId()).orElse(null);
        if (config == null || !config.isCategoryAutoEnabled()) {
            return;
        }
        var eligible = categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(context.currentCompany().getId())
                .stream().filter(category -> !category.isManualOnly()).toList();
        members.findByCompanyIdOrderByCustomerFiscalNameAsc(context.currentCompany().getId()).stream()
                .filter(Member::isActive)
                .filter(member -> !member.isAutoCategoryLocked())
                .filter(member -> member.getMemberCategory() == null || !member.getMemberCategory().isManualOnly())
                .forEach(member -> member.setCategory(eligible.stream()
                        .filter(category -> member.getMemberPoints() >= category.getMinPoints())
                        .findFirst().orElse(null), false));
    }

    private void ensureUniqueAutomaticThreshold(UUID excludedId, long minPoints, boolean manualOnly, boolean active) {
        if (manualOnly || !active) {
            return;
        }
        var duplicate = categories.findByCompanyIdOrderBySortOrderAscMinPointsAscNameAsc(
                        context.currentCompany().getId()).stream()
                .filter(MemberCategory::isActive)
                .filter(category -> !category.isManualOnly())
                .filter(category -> excludedId == null || !category.getId().equals(excludedId))
                .anyMatch(category -> category.getMinPoints() == minPoints);
        if (duplicate) {
            throw new IllegalStateException("message.member_category.duplicate_min_points");
        }
    }

    private void auditCategory(String event, MemberCategory category) {
        if (audit == null) {
            return;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("companyId", category.getCompany().getId());
        details.put("categoryId", category.getId());
        details.put("code", category.getCode());
        details.put("name", category.getName());
        details.put("minPoints", category.getMinPoints());
        details.put("discountPercent", category.getDiscountPercent());
        details.put("discountEnabled", category.isDiscountEnabled());
        details.put("manualOnly", category.isManualOnly());
        details.put("active", category.isActive());
        details.put("sortOrder", category.getSortOrder());
        audit.record(event, com.tpverp.backend.audit.AuditResult.EXITO, Map.copyOf(details));
    }

    private java.util.Optional<MemberCategory> initialCategory() {
        return categories.findByCompanyIdAndActiveTrueOrderByMinPointsAscNameAsc(
                        context.currentCompany().getId())
                .stream()
                .filter(category -> !category.isManualOnly())
                .filter(category -> category.getMinPoints() <= 0)
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
        // Return balance recovery has its own typed, idempotent contract.
        // Do not publish a second generic movement event for that operation;
        // all other member movements retain the legacy sync publication.
        if (type != MemberMovementType.DEVOLUCION_ACUMULACION_SALDO) {
            syncOutbox.enqueue(new SyncOutboundEventCommand(
                    context.currentCompany().getId(),
                    context.currentStore().getId(),
                    null,
                    "MEMBER_MOVEMENT",
                    saved.getId(),
                    SyncOperation.CREAR,
                    Map.of("memberId", member.getId().toString(), "type", type.name())));
        }
        return saved;
    }

    private MemberMovementView categoryMovement(
            Member member,
            UUID previousCategoryId,
            UUID newCategoryId,
            boolean lockAutomatic,
            String reason) {
        var saved = saveMovement(
                member,
                null,
                MemberMovementType.CAMBIO_CATEGORIA,
                BigDecimal.ZERO,
                0,
                previousCategoryId,
                newCategoryId,
                reason);
        saved.setCategoryAssignmentMetadata(lockAutomatic);
        return MemberMovementView.from(saved);
    }

    private void publishPointsOperation(
            UUID operationId,
            Member member,
            MemberPointsOperationType operationType,
            long amount,
            UUID sourceDocumentId,
            UUID originalDocumentId,
            Instant occurredAt,
            long pointsBefore,
            long debtBefore,
            String sourceCheckpoint,
            ProjectionDecision projection) {
        if (pointsSyncPublisher == null) {
            // Package-private compatibility path for isolated legacy unit tests.
            return;
        }
        pointsSyncPublisher.publishCreated(new MemberPointsOperation(
                operationId,
                member,
                context.currentCompany().getId(),
                context.currentStore().getId(),
                projection.storeSequence(),
                operationType,
                amount,
                sourceDocumentId,
                originalDocumentId,
                occurredAt,
                Math.subtractExact(member.getMemberPoints(), pointsBefore),
                Math.subtractExact(member.getLoyaltyPointsDebt(), debtBefore),
                sourceCheckpoint));
    }

    private ProjectionDecision allocatePointsProjection() {
        if (pointsProjectionCoordinator == null) {
            // Package-private compatibility path for isolated legacy unit tests.
            return new ProjectionDecision(1, true);
        }
        return pointsProjectionCoordinator.allocate(
                context.currentCompany().getId(), context.currentStore().getId());
    }

    private static UUID saleEarnOperationId(
            UUID documentId,
            BigDecimal previousEligiblePaidAmount,
            String sourceCheckpoint) {
        if (previousEligiblePaidAmount.signum() == 0) {
            return documentId;
        }
        return UUID.nameUUIDFromBytes((
                "MEMBER_POINTS_OPERATION|SALE_EARN|"
                        + documentId
                        + "|"
                        + sourceCheckpoint)
                .getBytes(StandardCharsets.UTF_8));
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
            throw new MemberBalanceOfficialSyncRequiredException();
        }
    }

    private BigDecimal requireAvailableBalance(
            Member member,
            MemberBalanceLotType balanceType,
            BigDecimal aggregateBalance,
            BigDecimal requested,
            Instant now,
            Optional<Instant> expiryBlockStartedAt) {
        var availableLots = lots.findByMemberIdAndAmountRemainingGreaterThan(
                        member.getId(), BigDecimal.ZERO)
                .stream()
                .filter(lot -> lot.getBalanceType() == balanceType)
                .filter(lot -> isLotAvailableAt(lot, now, expiryBlockStartedAt))
                .map(MemberBalanceLot::getAmountRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var available = PartyValues.money(availableLots.min(aggregateBalance));
        if (available.compareTo(PartyValues.money(requested)) < 0) {
            throw new IllegalStateException("message.member.balance_insufficient_or_expired");
        }
        return available;
    }

    private void consumeLots(
            Member member,
            MemberMovement movement,
            MemberBalanceLotType balanceType,
            BigDecimal amount,
            Instant now,
            Optional<Instant> expiryBlockStartedAt) {
        var remaining = amount;
        var orderedLots = lots.findByMemberIdAndAmountRemainingGreaterThan(member.getId(), BigDecimal.ZERO)
                .stream()
                .filter(lot -> lot.getBalanceType() == balanceType)
                .filter(lot -> isLotAvailableAt(lot, now, expiryBlockStartedAt))
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

    private Optional<Instant> expirationBlockStartedAt(UUID memberId, Instant now) {
        if (localBalanceReservations == null) {
            return Optional.empty();
        }
        return localBalanceReservations.findExpiryBlockStartedAt(
                memberId, EXPIRY_BLOCKING_RESERVATION_STATUSES, now);
    }

    private static boolean isLotAvailableAt(
            MemberBalanceLot lot,
            Instant now,
            Optional<Instant> expiryBlockStartedAt) {
        return lot.getExpiredAt() == null
                && (lot.getExpiresAt() == null
                        || lot.getExpiresAt().isAfter(now)
                        || isExpirationProtected(lot, expiryBlockStartedAt));
    }

    private static boolean isExpirationProtected(
            MemberBalanceLot lot,
            Optional<Instant> expiryBlockStartedAt) {
        return lot.getExpiresAt() != null
                && expiryBlockStartedAt
                        .filter(startedAt -> lot.getExpiresAt().isAfter(startedAt))
                        .isPresent();
    }

    private static BigDecimal availableForType(
            List<MemberBalanceLot> lots,
            MemberBalanceLotType type,
            BigDecimal aggregateBalance) {
        var lotBalance = lots.stream()
                .filter(lot -> lot.getBalanceType() == type)
                .map(MemberBalanceLot::getAmountRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return PartyValues.money(lotBalance.min(aggregateBalance));
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
            BigDecimal returnCreditBalance,
            long points,
            UUID categoryId,
            BigDecimal officialBalance,
            BigDecimal officialReturnCreditBalance,
            long officialPoints,
            Instant officialSyncedAt,
            boolean autoCategoryLocked,
            boolean active) {

        static MemberView from(Member member) {
            return new MemberView(
                    member.getId(), member.getCustomer().getId(), member.getMemberId(),
                    member.getNumMember(), member.getMemberBalance(),
                    member.getReturnCreditBalance(), member.getMemberPoints(),
                    member.getMemberCategory() == null ? null : member.getMemberCategory().getId(),
                    member.getOfficialMemberBalance(),
                    member.getOfficialReturnCreditBalance(), member.getOfficialMemberPoints(),
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
            BigDecimal returnCreditBalance,
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
                    member.getMemberSince(), member.getMemberBalance(),
                    member.getReturnCreditBalance(), member.getMemberPoints(),
                    category == null ? null : category.getId(),
                    category == null ? null : category.getName(),
                    member.isActive(), customer.isActive(), customer.getClientId(),
                    customer.getFiscalName(), customer.getDocumentType(), customer.getDocumentNumber(),
                    customer.getPhone(), customer.getEmail());
        }
    }

    private static String normalizeDocumentNumber(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record MemberWalletView(
            BigDecimal loyaltyAvailable,
            BigDecimal returnCreditAvailable,
            BigDecimal totalAvailable,
            List<MemberBalanceLotView> lots) {
    }

    public record MemberBalanceLotView(
            UUID id,
            MemberBalanceLotType type,
            UUID documentId,
            String documentNumber,
            MemberMovementType sourceMovementType,
            BigDecimal originalAmount,
            BigDecimal availableAmount,
            Instant obtainedAt,
            Instant expiresAt) {

        static MemberBalanceLotView from(MemberBalanceLot lot, String documentNumber) {
            return new MemberBalanceLotView(
                    lot.getId(), lot.getBalanceType(), lot.getDocumentId(), documentNumber,
                    lot.getSourceMovement() == null
                            ? null : lot.getSourceMovement().getType(),
                    lot.getAmountOriginal(), lot.getAmountRemaining(),
                    lot.getCreatedAt(), lot.getExpiresAt());
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
            UUID documentId,
            UUID previousCategoryId,
            UUID newCategoryId,
            String reason,
            Instant createdAt) {

        static MemberMovementView from(MemberMovement movement) {
                return new MemberMovementView(
                    movement.getId(), movement.getType(), movement.getBalanceAmount(),
                    movement.getPointsAmount(), movement.getDocumentId(),
                    movement.getPreviousCategoryId(), movement.getNewCategoryId(),
                    movement.getReason(), movement.getCreatedAt());
        }
    }

    public record MemberCategoryCommand(
            String name,
            long minPoints,
            BigDecimal discountPercent,
            boolean discountEnabled,
            boolean manualOnly,
            int sortOrder) {

        public MemberCategoryCommand(String name, long minPoints, BigDecimal discountPercent,
                boolean discountEnabled, int sortOrder) {
            this(name, minPoints, discountPercent, discountEnabled, false, sortOrder);
        }
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
            boolean balanceAccrualEnabled,
            BigDecimal balanceAccrualBaseAmount,
            BigDecimal balanceAccrualPercent,
            BalanceExpirationPolicy balanceExpirationPolicy,
            boolean pointsAccrualEnabled,
            BigDecimal pointsAccrualBaseAmount,
            BigDecimal pointsPerEuro,
            boolean categoryAutoEnabled,
            boolean memberWelcomeEnabled,
            MemberCardCodeFormat memberCardCodeFormat,
            String welcomeSubjectTemplate,
            String welcomeBodyTemplate) {
    }

    public record MemberSettingsView(
            boolean balanceAccrualEnabled,
            BigDecimal balanceAccrualBaseAmount,
            BigDecimal balanceAccrualPercent,
            BalanceExpirationPolicy balanceExpirationPolicy,
            boolean pointsAccrualEnabled,
            BigDecimal pointsAccrualBaseAmount,
            BigDecimal pointsPerEuro,
            boolean categoryAutoEnabled,
            boolean memberWelcomeEnabled,
            MemberCardCodeFormat memberCardCodeFormat,
            String welcomeSubjectTemplate,
            String welcomeBodyTemplate) {

        static MemberSettingsView from(MemberSettings settings) {
            return new MemberSettingsView(
                    settings.isBalanceAccrualEnabled(), settings.getBalanceAccrualBaseAmount(),
                    settings.getBalanceAccrualPercent(), settings.getBalanceExpirationPolicy(),
                    settings.isPointsAccrualEnabled(), settings.getPointsAccrualBaseAmount(),
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
