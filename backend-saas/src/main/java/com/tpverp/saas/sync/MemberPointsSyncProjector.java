package com.tpverp.saas.sync;

import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.loyalty.MemberPointsAuthorityStatus;
import com.tpverp.saas.loyalty.MemberPointsDebtAllocationStatus;
import com.tpverp.saas.loyalty.MemberPointsDebtLotStatus;
import com.tpverp.saas.loyalty.MemberPointsOperationStatus;
import com.tpverp.saas.loyalty.MemberPointsOperationType;
import com.tpverp.saas.loyalty.MemberPointsSettlementType;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccount;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccountRepository;
import com.tpverp.saas.loyalty.SaasMemberPointsBootstrapAbsorbedOperationRepository;
import com.tpverp.saas.loyalty.SaasMemberPointsAuthority;
import com.tpverp.saas.loyalty.SaasMemberPointsAuthorityRepository;
import com.tpverp.saas.loyalty.SaasMemberPointsDebtAllocation;
import com.tpverp.saas.loyalty.SaasMemberPointsDebtAllocationRepository;
import com.tpverp.saas.loyalty.SaasMemberPointsDebtLot;
import com.tpverp.saas.loyalty.SaasMemberPointsDebtLotRepository;
import com.tpverp.saas.loyalty.SaasMemberPointsOperation;
import com.tpverp.saas.loyalty.SaasMemberPointsOperationRepository;
import com.tpverp.saas.loyalty.SaasMemberPointsSettlement;
import com.tpverp.saas.loyalty.SaasMemberPointsSettlementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MemberPointsSyncProjector {

    public static final String ENTITY_TYPE = "MEMBER_POINTS_OPERATION";

    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "schemaVersion",
            "operationId",
            "memberId",
            "operationType",
            "amount",
            "sourceDocumentId",
            "originalDocumentId",
            "occurredAt",
            "localPointsDelta",
            "localDebtDelta"
    );

    private final EntityManager entityManager;
    private final SaasMemberPointsAuthorityRepository authorityRepository;
    private final SaasMemberBalanceAccountRepository accountRepository;
    private final SaasMemberPointsOperationRepository operationRepository;
    private final SaasMemberPointsDebtLotRepository debtLotRepository;
    private final SaasMemberPointsDebtAllocationRepository allocationRepository;
    private final SaasMemberPointsSettlementRepository settlementRepository;
    private SaasMemberPointsBootstrapAbsorbedOperationRepository absorbedOperations;

    public MemberPointsSyncProjector(
            EntityManager entityManager,
            SaasMemberPointsAuthorityRepository authorityRepository,
            SaasMemberBalanceAccountRepository accountRepository,
            SaasMemberPointsOperationRepository operationRepository,
            SaasMemberPointsDebtLotRepository debtLotRepository,
            SaasMemberPointsDebtAllocationRepository allocationRepository,
            SaasMemberPointsSettlementRepository settlementRepository
    ) {
        this.entityManager = entityManager;
        this.authorityRepository = authorityRepository;
        this.accountRepository = accountRepository;
        this.operationRepository = operationRepository;
        this.debtLotRepository = debtLotRepository;
        this.allocationRepository = allocationRepository;
        this.settlementRepository = settlementRepository;
    }

    @Autowired
    void setAbsorbedOperations(SaasMemberPointsBootstrapAbsorbedOperationRepository absorbedOperations) {
        this.absorbedOperations = absorbedOperations;
    }

    public boolean supports(String entityType, SyncOperation operation) {
        return ENTITY_TYPE.equals(entityType) && operation == SyncOperation.CREAR;
    }

    @Transactional(noRollbackFor = ProjectionException.class)
    public void project(SaasSyncEvent event, Map<String, Object> payload, Instant receivedAt) {
        if (event == null) {
            throw badRequest("El evento persistido es obligatorio");
        }
        if (!supports(event.getEntityType(), event.getOperation())) {
            throw badRequest("El evento no es una creacion de puntos soportada");
        }
        if (event.getCompany() == null || event.getCompany().getId() == null) {
            throw badRequest("El evento no tiene empresa autenticada");
        }
        if (event.getStore() == null || event.getStore().getId() == null) {
            throw badRequest("El evento no tiene tienda autenticada");
        }
        if (event.getEventId() == null || event.getEntityId() == null || receivedAt == null) {
            throw badRequest("El evento persistido esta incompleto");
        }

        UUID companyId = event.getCompany().getId();
        UUID storeId = event.getStore().getId();
        Long storeSequence = event.getStoreSequence();
        UUID eventId = event.getEventId();
        UUID entityId = event.getEntityId();
        if (storeId == null) {
            throw badRequest("El evento no tiene tienda autenticada");
        }

        ParsedOperation parsed = parse(payload);
        if (storeSequence != null && storeSequence <= 0) {
            throw badRequest("storeSequence debe ser positivo");
        }
        if (!parsed.operationId().equals(entityId)) {
            throw conflict("entityId no coincide con operationId");
        }

        lockCompany(companyId);
        var existing = operationRepository.findForUpdateByOperationId(companyId, parsed.operationId());
        if (existing.isPresent()) {
            if (existing.get().hasPayloadHash(parsed.payloadHash())) {
                return;
            }
            throw conflict("operationId reutilizado con contenido distinto");
        }
        if (storeSequence != null && operationRepository.findForUpdateByStoreSequence(
                companyId, storeId, storeSequence).isPresent()) {
            throw conflict("storeSequence reutilizado por otra operacion de puntos");
        }

        Instant now = receivedAt;
        SaasMemberPointsOperation operation = operationRepository.saveAndFlush(new SaasMemberPointsOperation(
                companyId,
                parsed.operationId(),
                eventId,
                storeId,
                storeSequence,
                event.getSchemaVersion(),
                parsed.memberId(),
                parsed.operationType(),
                parsed.amount(),
                parsed.sourceDocumentId(),
                parsed.originalDocumentId(),
                parsed.occurredAt(),
                parsed.localPointsDelta(),
                parsed.localDebtDelta(),
                parsed.payloadHash(),
                now
        ));

        var absorbed = absorbedOperations.findFirstByCompanyIdAndOperationId(companyId, parsed.operationId());
        if (absorbed.isPresent()) {
            if (!absorbed.get().getContractHash().equals(parsed.payloadHash())) {
                operation.markConflictWithoutState(
                        "La operacion difiere del manifiesto ABSORBED del bootstrap", now);
                throw conflict("operationId recibido con hash distinto al manifiesto ABSORBED");
            }
            operation.markAbsorbedBootstrap(now);
            return;
        }

        SaasMemberPointsAuthority authority = authorityRepository.findForUpdateByCompanyId(companyId)
                .orElseGet(() -> authorityRepository.saveAndFlush(new SaasMemberPointsAuthority(companyId, now)));
        if (!authority.isActive()) {
            return;
        }

        SaasMemberBalanceAccount account = loadOrCreateAccount(companyId, parsed.memberId());
        processOperation(operation, account);
        retryPendingDependencies(companyId, parsed.memberId(), account, operation.getId());
    }

    @Transactional
    public int activateAuthorityAndReplayPending(UUID companyId) {
        lockCompany(companyId);
        Instant now = Instant.now();
        SaasMemberPointsAuthority authority = authorityRepository.findForUpdateByCompanyId(companyId)
                .orElseGet(() -> authorityRepository.saveAndFlush(new SaasMemberPointsAuthority(companyId, now)));
        authority.activate(now);

        int processed = 0;
        List<SaasMemberPointsOperation> pending = operationRepository.findByCompanyIdAndStatusOrderByIdAsc(
                companyId,
                MemberPointsOperationStatus.PENDING_BOOTSTRAP
        );
        for (SaasMemberPointsOperation operation : pending) {
            if (operation.getStatus() != MemberPointsOperationStatus.PENDING_BOOTSTRAP) {
                continue;
            }
            SaasMemberBalanceAccount account = loadOrCreateAccount(companyId, operation.getMemberId());
            processOperation(operation, account);
            retryPendingDependencies(companyId, operation.getMemberId(), account, operation.getId());
            processed++;
        }
        return processed;
    }

    private void processOperation(
            SaasMemberPointsOperation operation,
            SaasMemberBalanceAccount account
    ) {
        Instant now = Instant.now();
        PointState before;
        try {
            before = readPointState(account);
        } catch (DomainConflict conflict) {
            operation.markConflictWithoutState(conflict.getMessage(), now);
            return;
        }

        try {
            DebtState debtState = lockAndValidateDebtState(operation, before.debt());
            switch (operation.getOperationType()) {
                case SALE_EARN -> applySale(operation, account, before, debtState, now);
                case RETURN_REVERSAL -> applyReturn(operation, account, before, now);
                case SALE_CANCELLATION -> applySaleCancellation(operation, account, before, now);
                case RETURN_CANCELLATION -> applyReturnCancellation(operation, account, before, now);
                case MANUAL_ADJUSTMENT -> applyManualAdjustment(operation, account, before);
            }
            PointState after = readPointState(account);
            operation.markApplied(before.points(), after.points(), before.debt(), after.debt(), now);
        } catch (PendingDependency dependency) {
            operation.markPendingDependency(dependency.getMessage(), before.points(), before.debt());
        } catch (DomainConflict conflict) {
            operation.markConflict(conflict.getMessage(), before.points(), before.debt(), now);
        }
    }

    private DebtState lockAndValidateDebtState(
            SaasMemberPointsOperation operation,
            BigDecimal accountDebt
    ) {
        List<SaasMemberPointsDebtLot> lots = debtLotRepository.findOutstandingForUpdate(
                operation.getCompanyId(),
                operation.getMemberId(),
                MemberPointsDebtLotStatus.ACTIVE
        );
        BigDecimal total = lots.stream()
                .map(SaasMemberPointsDebtLot::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(accountDebt) != 0) {
            throw new DomainConflict("pointsDebt no cuadra con los lotes de deuda activos");
        }
        return new DebtState(lots);
    }

    private void applySale(
            SaasMemberPointsOperation operation,
            SaasMemberBalanceAccount account,
            PointState before,
            DebtState debtState,
            Instant now
    ) {
        UUID documentId = requireSourceDocument(operation);
        assertSettlementDoesNotExist(operation.getCompanyId(), documentId, MemberPointsSettlementType.SALE);

        BigDecimal debtToPay = operation.getAmount().min(before.debt());
        BigDecimal pointsAwarded = operation.getAmount().subtract(debtToPay);
        BigDecimal newPoints = checkedState(before.points().add(pointsAwarded), "points");
        BigDecimal newDebt = checkedState(before.debt().subtract(debtToPay), "pointsDebt");

        List<AllocationPlan> plans = new ArrayList<>();
        BigDecimal remaining = debtToPay;
        for (SaasMemberPointsDebtLot lot : debtState.lots()) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal allocated = remaining.min(lot.getRemainingAmount());
            plans.add(new AllocationPlan(lot, allocated));
            remaining = remaining.subtract(allocated);
        }
        if (remaining.signum() != 0) {
            throw new DomainConflict("No existen lotes suficientes para liquidar pointsDebt");
        }

        UUID settlementId = operation.getOperationId();
        settlementRepository.save(new SaasMemberPointsSettlement(
                settlementId,
                operation.getCompanyId(),
                operation.getMemberId(),
                documentId,
                operation.getOriginalDocumentId(),
                operation.getOperationId(),
                MemberPointsSettlementType.SALE,
                operation.getAmount(),
                pointsAwarded,
                debtToPay,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                now
        ));
        for (AllocationPlan plan : plans) {
            plan.lot().settle(plan.amount());
            allocationRepository.save(new SaasMemberPointsDebtAllocation(
                    operation.getCompanyId(),
                    operation.getMemberId(),
                    settlementId,
                    plan.lot().getId(),
                    plan.amount(),
                    now
            ));
        }
        account.replacePoints(newPoints, newDebt);
    }

    private void applyReturn(
            SaasMemberPointsOperation operation,
            SaasMemberBalanceAccount account,
            PointState before,
            Instant now
    ) {
        UUID documentId = requireSourceDocument(operation);
        assertSettlementDoesNotExist(operation.getCompanyId(), documentId, MemberPointsSettlementType.RETURN);

        BigDecimal pointsRemoved = before.points().min(operation.getAmount());
        BigDecimal debtCreated = operation.getAmount().subtract(pointsRemoved);
        BigDecimal newPoints = checkedState(before.points().subtract(pointsRemoved), "points");
        BigDecimal newDebt = checkedState(before.debt().add(debtCreated), "pointsDebt");

        UUID debtLotId = null;
        if (debtCreated.signum() > 0) {
            debtLotId = operation.getOperationId();
            debtLotRepository.save(new SaasMemberPointsDebtLot(
                    debtLotId,
                    operation.getCompanyId(),
                    operation.getMemberId(),
                    operation.getOperationId(),
                    documentId,
                    MemberPointsOperationType.RETURN_REVERSAL,
                    debtCreated,
                    operation.getId(),
                    now
            ));
        }
        settlementRepository.save(new SaasMemberPointsSettlement(
                operation.getOperationId(),
                operation.getCompanyId(),
                operation.getMemberId(),
                documentId,
                operation.getOriginalDocumentId(),
                operation.getOperationId(),
                MemberPointsSettlementType.RETURN,
                operation.getAmount(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                pointsRemoved,
                debtCreated,
                debtLotId,
                now
        ));
        account.replacePoints(newPoints, newDebt);
    }

    private void applySaleCancellation(
            SaasMemberPointsOperation operation,
            SaasMemberBalanceAccount account,
            PointState before,
            Instant now
    ) {
        SaasMemberPointsSettlement settlement = requireOriginalSettlement(
                operation,
                MemberPointsSettlementType.SALE
        );
        validateCancellation(operation, settlement);

        List<SaasMemberPointsDebtAllocation> allocations =
                allocationRepository.findForUpdateBySettlementId(settlement.getOperationId());

        BigDecimal reopenedDebt = BigDecimal.ZERO;
        BigDecimal pointsToWithdraw = settlement.getPointsAwarded();
        List<AllocationReversalPlan> plans = new ArrayList<>();
        for (SaasMemberPointsDebtAllocation allocation : allocations) {
            if (allocation.getStatus() != MemberPointsDebtAllocationStatus.APPLIED) {
                throw new DomainConflict("La venta contiene una asignacion de deuda ya revertida");
            }
            SaasMemberPointsDebtLot lot = debtLotRepository.findForUpdateById(
                            allocation.getDebtLotId(),
                            operation.getCompanyId()
                    )
                    .orElseThrow(() -> new DomainConflict("No existe el lote de deuda asignado a la venta"));
            boolean reopen = lot.isActive();
            plans.add(new AllocationReversalPlan(allocation, lot, reopen));
            if (reopen) {
                reopenedDebt = reopenedDebt.add(allocation.getAmount());
            } else {
                pointsToWithdraw = pointsToWithdraw.add(allocation.getAmount());
            }
        }

        BigDecimal pointsRemoved = before.points().min(pointsToWithdraw);
        BigDecimal cancellationDebt = pointsToWithdraw.subtract(pointsRemoved);
        BigDecimal newPoints = checkedState(before.points().subtract(pointsRemoved), "points");
        BigDecimal newDebt = checkedState(before.debt().add(reopenedDebt).add(cancellationDebt), "pointsDebt");

        for (AllocationReversalPlan plan : plans) {
            if (plan.reopen()) {
                plan.lot().reopen(plan.allocation().getAmount());
                plan.allocation().reopen(now);
            } else {
                plan.allocation().convertToPointsReversal(now);
            }
        }
        if (cancellationDebt.signum() > 0) {
            debtLotRepository.save(new SaasMemberPointsDebtLot(
                    operation.getOperationId(),
                    operation.getCompanyId(),
                    operation.getMemberId(),
                    operation.getOperationId(),
                    operation.getSourceDocumentId(),
                    MemberPointsOperationType.SALE_CANCELLATION,
                    cancellationDebt,
                    operation.getId(),
                    now
            ));
        }
        settlement.cancel(operation.getOperationId(), now);
        account.replacePoints(newPoints, newDebt);
    }

    private void applyReturnCancellation(
            SaasMemberPointsOperation operation,
            SaasMemberBalanceAccount account,
            PointState before,
            Instant now
    ) {
        SaasMemberPointsSettlement settlement = requireOriginalSettlement(
                operation,
                MemberPointsSettlementType.RETURN
        );
        validateCancellation(operation, settlement);

        BigDecimal remainingDebt = BigDecimal.ZERO;
        BigDecimal paidDebt = BigDecimal.ZERO;
        SaasMemberPointsDebtLot lot = null;
        if (settlement.getDebtCreated().signum() > 0) {
            if (settlement.getDebtLotId() == null) {
                throw new DomainConflict("El retorno no conserva el lote de deuda esperado");
            }
            lot = debtLotRepository.findForUpdateById(settlement.getDebtLotId(), operation.getCompanyId())
                    .orElseThrow(() -> new DomainConflict("No existe el lote de deuda del retorno"));
            if (!lot.isActive()) {
                throw new DomainConflict("El lote de deuda del retorno ya fue cancelado");
            }
            remainingDebt = lot.getRemainingAmount();
            paidDebt = settlement.getDebtCreated().subtract(remainingDebt);
            if (paidDebt.signum() < 0) {
                throw new DomainConflict("El remanente del lote supera la deuda original del retorno");
            }
        }

        BigDecimal restoredPoints = settlement.getPointsRemoved().add(paidDebt);
        BigDecimal newPoints = checkedState(before.points().add(restoredPoints), "points");
        BigDecimal newDebt = checkedState(before.debt().subtract(remainingDebt), "pointsDebt");
        if (lot != null) {
            lot.cancel(now);
        }
        settlement.cancel(operation.getOperationId(), now);
        account.replacePoints(newPoints, newDebt);
    }

    private void applyManualAdjustment(
            SaasMemberPointsOperation operation,
            SaasMemberBalanceAccount account,
            PointState before
    ) {
        BigDecimal amount = operation.getAmount();
        if (amount.signum() > 0) {
            account.replacePoints(checkedState(before.points().add(amount), "points"), before.debt());
            return;
        }
        BigDecimal removal = amount.negate();
        if (before.points().compareTo(removal) < 0) {
            throw new DomainConflict("El ajuste manual negativo supera los puntos disponibles");
        }
        account.replacePoints(before.points().subtract(removal), before.debt());
    }

    private void retryPendingDependencies(
            UUID companyId,
            UUID memberId,
            SaasMemberBalanceAccount account,
            Long excludedOperationId
    ) {
        List<SaasMemberPointsOperation> pending =
                operationRepository.findByCompanyIdAndMemberIdAndStatusOrderByIdAsc(
                        companyId,
                        memberId,
                        MemberPointsOperationStatus.PENDING_DEPENDENCY
                );
        for (SaasMemberPointsOperation candidate : pending) {
            if (candidate.getId().equals(excludedOperationId)) {
                continue;
            }
            processOperation(candidate, account);
        }
    }

    private SaasMemberPointsSettlement requireOriginalSettlement(
            SaasMemberPointsOperation operation,
            MemberPointsSettlementType type
    ) {
        UUID originalDocumentId = operation.getOriginalDocumentId();
        if (originalDocumentId == null) {
            throw new DomainConflict("originalDocumentId es obligatorio para una cancelacion");
        }
        return settlementRepository.findForUpdateByDocument(operation.getCompanyId(), originalDocumentId, type)
                .orElseThrow(() -> new PendingDependency("Aun no existe el settlement del documento original"));
    }

    private void validateCancellation(
            SaasMemberPointsOperation operation,
            SaasMemberPointsSettlement settlement
    ) {
        if (!settlement.getMemberId().equals(operation.getMemberId())) {
            throw new DomainConflict("El settlement original pertenece a otro socio");
        }
        if (settlement.isCancelled()) {
            throw new DomainConflict("El settlement ya tiene una cancelacion aplicada");
        }
        if (settlement.getAmount().compareTo(operation.getAmount()) != 0) {
            throw new DomainConflict("La cancelacion no coincide con el importe del settlement original");
        }
    }

    private void assertSettlementDoesNotExist(
            UUID companyId,
            UUID documentId,
            MemberPointsSettlementType type
    ) {
        if (settlementRepository.findForUpdateByDocument(companyId, documentId, type).isPresent()) {
            throw new DomainConflict("El documento ya tiene un settlement de puntos");
        }
    }

    private UUID requireSourceDocument(SaasMemberPointsOperation operation) {
        if (operation.getSourceDocumentId() == null) {
            throw new DomainConflict("sourceDocumentId es obligatorio para ventas y devoluciones");
        }
        return operation.getSourceDocumentId();
    }

    private SaasMemberBalanceAccount loadOrCreateAccount(UUID companyId, UUID memberId) {
        return accountRepository.findForUpdate(companyId, memberId)
                .orElseGet(() -> accountRepository.saveAndFlush(new SaasMemberBalanceAccount(companyId, memberId)));
    }

    private void lockCompany(UUID companyId) {
        SaasCompany company = entityManager.find(SaasCompany.class, companyId, LockModeType.PESSIMISTIC_WRITE);
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La empresa autenticada no existe");
        }
    }

    private PointState readPointState(SaasMemberBalanceAccount account) {
        return new PointState(
                checkedState(account.getPoints(), "points"),
                checkedState(account.getPointsDebt(), "pointsDebt")
        );
    }

    private BigDecimal checkedState(BigDecimal value, String field) {
        if (value == null) {
            throw new DomainConflict(field + " no puede ser null");
        }
        BigDecimal integer;
        try {
            integer = value.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new DomainConflict(field + " contiene puntos decimales");
        }
        if (integer.signum() < 0 || integer.precision() > 19) {
            throw new DomainConflict(field + " esta fuera del rango permitido");
        }
        return integer;
    }

    private ParsedOperation parse(Map<String, Object> payload) {
        if (payload == null) {
            throw badRequest("payload debe ser un objeto JSON");
        }
        for (String field : payload.keySet()) {
            if (!PAYLOAD_FIELDS.contains(field)) {
                throw badRequest("Campo no permitido en payload: " + field);
            }
        }

        BigDecimal schemaVersion = parseInteger(required(payload, "schemaVersion"), "schemaVersion");
        if (schemaVersion.compareTo(BigDecimal.ONE) != 0) {
            throw badRequest("schemaVersion debe ser 1");
        }
        UUID operationId = parseUuid(required(payload, "operationId"), "operationId");
        UUID memberId = parseUuid(required(payload, "memberId"), "memberId");
        MemberPointsOperationType type = parseOperationType(required(payload, "operationType"));
        BigDecimal amount = parseInteger(required(payload, "amount"), "amount");
        if (type == MemberPointsOperationType.MANUAL_ADJUSTMENT) {
            if (amount.signum() == 0) {
                throw badRequest("amount no puede ser cero en MANUAL_ADJUSTMENT");
            }
        } else if (amount.signum() < 0) {
            throw badRequest("amount debe ser no negativo");
        }
        UUID sourceDocumentId = parseOptionalUuid(payload.get("sourceDocumentId"), "sourceDocumentId");
        UUID originalDocumentId = parseOptionalUuid(payload.get("originalDocumentId"), "originalDocumentId");
        Instant occurredAt = parseInstant(required(payload, "occurredAt"));
        BigDecimal localPointsDelta = parseInteger(required(payload, "localPointsDelta"), "localPointsDelta");
        BigDecimal localDebtDelta = parseInteger(required(payload, "localDebtDelta"), "localDebtDelta");

        String canonical = "1|"
                + operationId + "|"
                + memberId + "|"
                + type.name() + "|"
                + amount.toPlainString() + "|"
                + optionalUuid(sourceDocumentId) + "|"
                + optionalUuid(originalDocumentId) + "|"
                + occurredAt + "|"
                + localPointsDelta.toPlainString() + "|"
                + localDebtDelta.toPlainString() + "\n";
        return new ParsedOperation(
                operationId,
                memberId,
                type,
                amount,
                sourceDocumentId,
                originalDocumentId,
                occurredAt,
                localPointsDelta,
                localDebtDelta,
                sha256(canonical)
        );
    }

    private Object required(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            throw badRequest(field + " es obligatorio");
        }
        return value;
    }

    private BigDecimal parseInteger(Object value, String field) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw badRequest(field + " debe ser un entero");
        }
        String text = value.toString();
        if (!INTEGER_PATTERN.matcher(text).matches()) {
            throw badRequest(field + " debe ser un entero decimal sin exponente");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text).setScale(0, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw badRequest(field + " no es valido");
        }
        if (parsed.precision() > 19) {
            throw badRequest(field + " supera 19 digitos");
        }
        return parsed;
    }

    private UUID parseUuid(Object value, String field) {
        if (!(value instanceof String text)) {
            throw badRequest(field + " debe ser UUID textual");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " no es un UUID valido");
        }
    }

    private UUID parseOptionalUuid(Object value, String field) {
        if (value == null) {
            return null;
        }
        return parseUuid(value, field);
    }

    private MemberPointsOperationType parseOperationType(Object value) {
        if (!(value instanceof String text)) {
            throw badRequest("operationType debe ser texto");
        }
        try {
            return MemberPointsOperationType.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw badRequest("operationType no soportado");
        }
    }

    private Instant parseInstant(Object value) {
        if (!(value instanceof String text)) {
            throw badRequest("occurredAt debe ser texto ISO-8601");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException exception) {
            throw badRequest("occurredAt no es un Instant valido");
        }
    }

    private String optionalUuid(UUID value) {
        return value == null ? "-" : value.toString();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private ProjectionException badRequest(String message) {
        return new ProjectionException(HttpStatus.BAD_REQUEST, message);
    }

    private ProjectionException conflict(String message) {
        return new ProjectionException(HttpStatus.CONFLICT, message);
    }

    private record ParsedOperation(
            UUID operationId,
            UUID memberId,
            MemberPointsOperationType operationType,
            BigDecimal amount,
            UUID sourceDocumentId,
            UUID originalDocumentId,
            Instant occurredAt,
            BigDecimal localPointsDelta,
            BigDecimal localDebtDelta,
            String payloadHash
    ) {
    }

    private record PointState(BigDecimal points, BigDecimal debt) {
    }

    private record DebtState(List<SaasMemberPointsDebtLot> lots) {
    }

    private record AllocationPlan(SaasMemberPointsDebtLot lot, BigDecimal amount) {
    }

    private record AllocationReversalPlan(
            SaasMemberPointsDebtAllocation allocation,
            SaasMemberPointsDebtLot lot,
            boolean reopen
    ) {
    }

    private static final class PendingDependency extends RuntimeException {
        private PendingDependency(String message) {
            super(message);
        }
    }

    private static final class DomainConflict extends RuntimeException {
        private DomainConflict(String message) {
            super(message);
        }
    }

    public static final class ProjectionException extends ResponseStatusException {
        private ProjectionException(HttpStatus status, String reason) {
            super(status, reason);
        }
    }
}
