package com.tpverp.backend.cash;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashSessionService {

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CashCloseOperationRepository closeOperations;
    private final CashReconciliationAttemptRepository reconciliationAttempts;
    private final CashStoreConfigRepository configs;
    private final TerminalRepository terminals;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;
    private final SaleOperationSecurityService operationSecurity;
    private final CashAmountCalculator calculator;
    private final SyncOutboxService syncOutbox;
    private final AuditService audit;
    private final ControlAlertDetectionService controlAlerts;
    private final Clock clock;

    public CashSessionService(
            CashSessionRepository sessions,
            CashMovementRepository movements,
            CashCloseOperationRepository closeOperations,
            CashReconciliationAttemptRepository reconciliationAttempts,
            CashStoreConfigRepository configs,
            TerminalRepository terminals,
            CurrentOrganization organization,
            CashPermissionService permissions,
            SaleOperationSecurityService operationSecurity,
            CashAmountCalculator calculator,
            SyncOutboxService syncOutbox,
            AuditService audit,
            ControlAlertDetectionService controlAlerts,
            Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.closeOperations = closeOperations;
        this.reconciliationAttempts = reconciliationAttempts;
        this.configs = configs;
        this.terminals = terminals;
        this.organization = organization;
        this.permissions = permissions;
        this.operationSecurity = operationSecurity;
        this.calculator = calculator;
        this.syncOutbox = syncOutbox;
        this.audit = audit;
        this.controlAlerts = controlAlerts;
        this.clock = clock;
    }

    // Returns cash status while filtering theoretical amounts by permission.
    @Transactional(readOnly = true)
    public CashSessionView status(UUID terminalId, Authentication authentication) {
        permissions.requireCashStatusPermission(authentication);
        validateTerminal(terminalId);
        var session = openSession(terminalId);
        return view(session, permissions.canSeeExpectedTotals(authentication));
    }

    // Abre una sesion de caja con el fondo calculado para la terminal actual.
    @Transactional
    public CashSessionView open(UUID terminalId, Authentication authentication) {
        permissions.requireSalesPermission(authentication);
        var terminal = validateTerminalForCashSessionPreparation(terminalId);
        if (sessions.findByTerminalIdAndStatus(terminal.getId(), CashSessionStatus.ABIERTA).isPresent()) {
            throw new IllegalStateException("Ya existe una sesion de caja abierta para la terminal");
        }
        var hasPreviousClosed = sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                terminal.getId(), CashSessionStatus.CERRADA).isPresent();
        var betweenSessions = movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(terminal.getId());
        var hasBetweenSessionEntry = betweenSessions.stream()
                .anyMatch(movement -> movement.getType() == CashMovementType.ENTRADA_ENTRE_SESIONES);
        if (!hasPreviousClosed && !hasBetweenSessionEntry) {
            throw new IllegalStateException("La primera apertura requiere una entrada entre sesiones");
        }
        var user = organization.currentUser(authentication);
        var session = createSession(
                terminal,
                user,
                calculator.nextOpeningFund(terminal.getId()));
        return view(sessions.save(session), permissions.canSeeExpectedTotals(authentication));
    }

    // Prepara la entrada a Ventas: bloquea si la politica exige apertura manual y,
    // en caso contrario, abre automaticamente conservando el fondo del cierre anterior.
    @Transactional
    public CashSalesSessionReadinessView prepareForSales(
            UUID terminalId,
            Authentication authentication) {
        permissions.requireSalesPermission(authentication);
        var terminal = validateTerminalForCashSessionPreparation(terminalId);
        var existing = sessions.findByTerminalIdAndStatus(terminal.getId(), CashSessionStatus.ABIERTA);
        var cashConfig = config(terminal.getTienda().getId());
        if (existing.isPresent()) {
            return readiness(cashConfig, existing.get(), authentication);
        }
        if (cashConfig.isCashSessionRequired()) {
            return new CashSalesSessionReadinessView(
                    true,
                    false,
                    null,
                    cashConfig.isRequireEntryBreakdown(),
                    CashDenomination.valuesInEuroOrder(),
                    cashConfig.isRequireWithdrawalBreakdown(),
                    CashDenomination.valuesInEuroOrder());
        }
        var hasPreviousClosed = sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                terminal.getId(), CashSessionStatus.CERRADA).isPresent();
        var betweenSessions = movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(terminal.getId());
        var firstOpening = !hasPreviousClosed && betweenSessions.isEmpty();
        var openingFund = firstOpening ? Money.euros("0") : calculator.nextOpeningFund(terminal.getId());
        var session = createSession(
                terminal,
                organization.currentUser(authentication),
                openingFund);
        return readiness(cashConfig, sessions.save(session), authentication);
    }

    // Records a manual entry in an open session after resolving the configured F9 policy.
    @Transactional
    public CashMovementView entry(UUID terminalId, CashEntryRequest request, Authentication authentication) {
        permissions.requireCashMovementAccess(authentication);
        validateTerminal(terminalId);
        if (request.comment() == null || request.comment().isBlank()) {
            throw new IllegalArgumentException("El comentario es obligatorio");
        }
        var session = openSession(terminalId);
        var amount = positiveAmount(request.amount());
        validateDenominations(amount, request.denominations(), config(session).isRequireEntryBreakdown());
        var authorization = operationSecurity.authorize(
                SaleOperationCode.CASH_MOVEMENT,
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication);
        var movement = CashMovement.sessionMovement(
                session.getStoreId(), session.getTerminalId(), session, CashMovementType.ENTRADA,
                amount, Instant.now(clock),
                authorization.operator().getId(),
                authorization.authorizer().getId(),
                request.comment(), null, null);
        addDenominations(movement, request.denominations());
        return CashMovementView.from(movements.save(movement));
    }

    // Records a cash withdrawal in an open session.
    @Transactional
    public CashMovementView withdrawal(UUID terminalId, CashWithdrawalRequest request, Authentication authentication) {
        permissions.requireCashMovementAccess(authentication);
        validateTerminal(terminalId);
        if (request.comment() == null || request.comment().isBlank()) {
            throw new IllegalArgumentException("El motivo de la retirada es obligatorio");
        }
        var session = openSession(terminalId);
        var amount = positiveAmount(request.amount());
        validateDenominations(amount, request.denominations(), config(session).isRequireWithdrawalBreakdown());
        var authorization = operationSecurity.authorize(
                SaleOperationCode.CASH_MOVEMENT,
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication);
        if (amount.compareTo(calculator.availableCash(session)) > 0) {
            throw new IllegalArgumentException("message.cash.withdrawal_exceeds_available_cash");
        }
        var movement = CashMovement.sessionMovement(
                session.getStoreId(), session.getTerminalId(), session, CashMovementType.RETIRADA,
                amount, Instant.now(clock),
                authorization.operator().getId(),
                authorization.authorizer().getId(),
                request.comment(), null, null);
        addDenominations(movement, request.denominations());
        return CashMovementView.from(movements.save(movement));
    }

    // Cierra una sesion mediante arqueo ciego, conservando abiertos los primeros descuadres fuera de tolerancia.
    @Transactional
    public CashSessionView close(UUID terminalId, CashCloseRequest request, Authentication authentication) {
        var operationId = Objects.requireNonNull(request.closeOperationId(), "closeOperationId");
        var attemptId = Objects.requireNonNull(
                request.reconciliationAttemptId(),
                "reconciliationAttemptId");
        closeOperations.lock("cash-close-terminal:" + terminalId);
        permissions.requireSalesPermission(authentication);
        var terminal = validateTerminal(terminalId);
        var existingOperation = closeOperations.findById(operationId);
        CashSession session;
        if (existingOperation.isPresent()) {
            var operation = existingOperation.orElseThrow();
            if (!operation.getStoreId().equals(terminal.getTienda().getId())
                    || !operation.getTerminalId().equals(terminalId)) {
                throw new IllegalStateException("La operacion de cierre pertenece a otra terminal");
            }
            session = sessions.findById(operation.getSessionId())
                    .orElseThrow(() -> new IllegalStateException("La sesion de la operacion no existe"));
        } else {
            session = openSession(terminalId);
            var operationForSession = closeOperations.findBySessionId(session.getId());
            if (operationForSession.isPresent()) {
                throw new IllegalStateException("La sesion ya tiene otra operacion de cierre iniciada");
            }
        }
        var authorization = operationSecurity.authorize(
                SaleOperationCode.CLOSE_CASH_SESSION,
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication);
        var cashConfig = config(session);
        var user = authorization.operator();
        var finalWithdrawal = nonNegativeAmount(request.finalWithdrawalAmount());
        validateDenominations(
                finalWithdrawal,
                request.finalWithdrawalDenominations(),
                finalWithdrawal.signum() > 0 && cashConfig.isRequireWithdrawalBreakdown());
        var withdrawalHash = closeWithdrawalHash(
                session,
                finalWithdrawal,
                request.finalWithdrawalComment(),
                request.finalWithdrawalDenominations());
        CashCloseOperation closeOperation;
        BigDecimal expectedCashAfterWithdrawal = null;
        if (existingOperation.isEmpty()) {
            var availableCash = calculator.availableCash(session);
            if (finalWithdrawal.compareTo(availableCash) > 0) {
                throw new IllegalArgumentException("message.cash.withdrawal_exceeds_available_cash");
            }
            UUID movementId = null;
            if (finalWithdrawal.signum() > 0) {
                var movement = CashMovement.sessionMovement(
                        session.getStoreId(), session.getTerminalId(), session, CashMovementType.RETIRADA_CIERRE,
                        finalWithdrawal, Instant.now(clock), user.getId(), authorization.authorizer().getId(),
                        request.finalWithdrawalComment(), null, null);
                addDenominations(movement, request.finalWithdrawalDenominations());
                movementId = movements.save(movement).getId();
            }
            closeOperation = closeOperations.saveAndFlush(CashCloseOperation.start(
                    operationId,
                    session.getStoreId(),
                    session.getTerminalId(),
                    session.getId(),
                    movementId,
                    finalWithdrawal,
                    request.finalWithdrawalComment(),
                    withdrawalHash,
                    Instant.now(clock)));
            expectedCashAfterWithdrawal = availableCash.subtract(finalWithdrawal);
        } else {
            closeOperation = existingOperation.orElseThrow();
            if (!closeOperation.matches(
                    session.getStoreId(), session.getTerminalId(), session.getId())
                    || !closeOperation.matchesWithdrawal(withdrawalHash, finalWithdrawal)
                    || finalWithdrawal.signum() > 0 && closeOperation.getWithdrawalMovementId() == null
                    || finalWithdrawal.signum() == 0 && closeOperation.getWithdrawalMovementId() != null) {
                throw new IllegalStateException(
                        "La operacion de cierre no coincide con la retirada final original");
            }
        }
        var retainedFund = nonNegativeAmount(request.retainedFund());
        validateDenominations(retainedFund, request.retainedFundDenominations(), cashConfig.isRequireClosingBreakdown());
        var attemptHash = closeAttemptHash(
                closeOperation,
                retainedFund,
                request.retainedFundDenominations());
        var replay = reconciliationAttempts.findByCloseOperationIdAndIdempotencyKey(
                closeOperation.getId(), attemptId);
        if (replay.isPresent()) {
            var attempt = replay.orElseThrow();
            if (!attempt.matches(closeOperation, attemptHash)) {
                throw new IllegalStateException(
                        "La clave idempotente del arqueo pertenece a otra solicitud");
            }
            return replayedAttemptView(
                    session,
                    permissions.canSeeExpectedTotals(authentication),
                    attempt);
        }
        if (session.getStatus() != CashSessionStatus.ABIERTA) {
            throw new IllegalStateException("La operacion de cierre ya esta completada");
        }
        var expectedCash = expectedCashAfterWithdrawal == null
                ? calculator.availableCash(session)
                : expectedCashAfterWithdrawal;
        var attempt = session.registerAttempt(
                user.getId(),
                Instant.now(clock),
                retainedFund,
                expectedCash,
                cashConfig.getDiscrepancyTolerance(),
                closeOperation,
                attemptId,
                attemptHash);
        if (attempt.closedSession() && isLateClose(session, attempt.getCreatedAt())) {
            session.markLateClosing();
        }
        sessions.save(session);
        closeOperation.recordAttempt(attempt.closedSession(), attempt.getCreatedAt());
        closeOperations.save(closeOperation);
        audit.record(
                "CASH_SESSION_CLOSE_ATTEMPT",
                AuditResult.EXITO,
                closeAuditDetails(session, authorization, attempt, closeOperation.getId()));
        controlAlerts.detectCashSessionDiscrepancy(
                session.getId(),
                session.getStoreId(),
                session.getTerminalId(),
                expectedCash,
                retainedFund,
                attempt.getDiscrepancy(),
                cashConfig.getDiscrepancyTolerance(),
                attempt.getAttemptNumber(),
                attempt.closedSession(),
                authorization.authorizer().getId(),
                authorization.authorizer().getUserName(),
                authorization.delegated(),
                authentication);
        if (attempt.closedSession()) {
            enqueueClosedSession(session);
        }
        return view(session, permissions.canSeeExpectedTotals(authentication), attempt);
    }

    @Transactional(readOnly = true)
    public CashCloseOperationView closeOperation(
            UUID terminalId,
            UUID operationId,
            Authentication authentication) {
        permissions.requireSalesPermission(authentication);
        var terminal = validateTerminal(terminalId);
        var operation = closeOperations.findById(operationId)
                .orElseThrow(() -> new NoSuchElementException("Operacion de cierre no encontrada"));
        if (!operation.getStoreId().equals(terminal.getTienda().getId())
                || !operation.getTerminalId().equals(terminalId)) {
            throw new NoSuchElementException("Operacion de cierre no encontrada");
        }
        var session = sessions.findById(operation.getSessionId())
                .orElseThrow(() -> new IllegalStateException("La sesion de la operacion no existe"));
        var latestAttempt = reconciliationAttempts
                .findFirstByCloseOperationIdOrderByAttemptNumberDesc(operationId);
        var includeExpectedTotals = permissions.canSeeExpectedTotals(authentication);
        return new CashCloseOperationView(
                operation.getId(),
                operation.getSessionId(),
                operation.getTerminalId(),
                operation.getStatus(),
                operation.getWithdrawalAmount(),
                operation.getWithdrawalComment(),
                latestAttempt.map(CashReconciliationAttempt::getIdempotencyKey).orElse(null),
                latestAttempt
                        .map(attempt -> replayedAttemptView(session, includeExpectedTotals, attempt))
                        .orElse(null));
    }

    private Map<String, Object> closeAuditDetails(
            CashSession session,
            Authorization authorization,
            CashReconciliationAttempt attempt,
            UUID closeOperationId) {
        var details = new LinkedHashMap<String, Object>();
        details.put("operationCode", SaleOperationCode.CLOSE_CASH_SESSION.name());
        details.put("sessionId", session.getId().toString());
        details.put("terminalId", session.getTerminalId().toString());
        details.put("operatorId", authorization.operator().getId().toString());
        details.put("operatorName", authorization.operator().getUserName());
        details.put("authorizerId", authorization.authorizer().getId().toString());
        details.put("authorizerName", authorization.authorizer().getUserName());
        details.put("delegated", authorization.delegated());
        details.put("reconciliationAttempt", attempt.getAttemptNumber());
        details.put("closed", attempt.closedSession());
        details.put("closeOperationId", closeOperationId.toString());
        details.put("reconciliationAttemptId", attempt.getIdempotencyKey().toString());
        return details;
    }

    private void enqueueClosedSession(CashSession session) {
        syncOutbox.enqueue(new SyncOutboundEventCommand(
                organization.currentStore().getEmpresa().getId(),
                session.getStoreId(),
                session.getTerminalId(),
                "CIERRE_CAJA",
                session.getId(),
                SyncOperation.CERRAR,
                Map.of(
                        "estado", session.getStatus().name(),
                        "abiertaEn", session.getOpenedAt().toString(),
                        "cerradaEn", session.getClosedAt().toString(),
                        "efectivoTeorico", session.getExpectedCash().toPlainString(),
                        "fondoDejado", session.getRetainedFund().toPlainString(),
                        "descuadre", session.getDiscrepancy().toPlainString(),
                        "cierreTardio", session.isLateClosing())));
    }

    // Records prepared cash between sessions when no cash session is open.
    @Transactional
    public CashMovementView betweenSessions(
            UUID terminalId, CashWithdrawalRequest request, Authentication authentication) {
        permissions.requireConfigPermission(authentication);
        var terminal = validateTerminal(terminalId);
        if (sessions.findByTerminalIdAndStatus(terminal.getId(), CashSessionStatus.ABIERTA).isPresent()) {
            throw new IllegalStateException("No se permiten movimientos entre sesiones con caja abierta");
        }
        var amount = positiveAmount(request.amount());
        var cashConfig = config(terminal.getTienda().getId());
        var breakdownRequired = request.withdrawal()
                ? cashConfig.isRequireWithdrawalBreakdown()
                : cashConfig.isRequireEntryBreakdown();
        validateDenominations(amount, request.denominations(), breakdownRequired);
        if (request.withdrawal() && amount.compareTo(calculator.nextOpeningFund(terminal.getId())) > 0) {
            throw new IllegalArgumentException("message.cash.withdrawal_exceeds_pending_opening_fund");
        }
        var createdAt = Instant.now(clock);
        var user = organization.currentUser(authentication);
        var movement = request.withdrawal()
                ? CashMovement.betweenSessionWithdrawal(
                        terminal.getTienda().getId(), terminal.getId(), amount, createdAt,
                        user.getId(), null, request.comment())
                : CashMovement.betweenSessionEntry(
                        terminal.getTienda().getId(), terminal.getId(), amount, createdAt,
                        user.getId(), null, request.comment());
        addDenominations(movement, request.denominations());
        return CashMovementView.from(movements.save(movement));
    }

    private Terminal validateTerminal(UUID terminalId) {
        var store = organization.currentStore();
        var terminal = terminals.findByIdAndTiendaId(terminalId, store.getId())
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
        if (!terminal.isActiva() || !terminal.isAprobada()) {
            throw new IllegalStateException("Terminal no activa o no aprobada");
        }
        return terminal;
    }

    private Terminal validateTerminalForCashSessionPreparation(UUID terminalId) {
        var store = organization.currentStore();
        var terminal = terminals.findForCashSessionPreparation(terminalId, store.getId())
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
        if (!terminal.isActiva() || !terminal.isAprobada()) {
            throw new IllegalStateException("Terminal no activa o no aprobada");
        }
        return terminal;
    }

    private CashSession openSession(UUID terminalId) {
        return sessions.findByTerminalIdAndStatus(terminalId, CashSessionStatus.ABIERTA)
                .orElseThrow(() -> new IllegalStateException("No hay una sesion de caja abierta"));
    }

    private CashSession createSession(Terminal terminal, UserAccount user, BigDecimal openingFund) {
        return CashSession.open(
                terminal.getTienda().getId(),
                terminal.getId(),
                user.getId(),
                Instant.now(clock),
                openingFund);
    }

    private CashSalesSessionReadinessView readiness(
            CashStoreConfig cashConfig,
            CashSession session,
            Authentication authentication) {
        return new CashSalesSessionReadinessView(
                cashConfig.isCashSessionRequired(),
                true,
                view(session, permissions.canSeeExpectedTotals(authentication)),
                cashConfig.isRequireEntryBreakdown(),
                CashDenomination.valuesInEuroOrder(),
                cashConfig.isRequireWithdrawalBreakdown(),
                CashDenomination.valuesInEuroOrder());
    }

    private CashSessionView view(CashSession session, boolean includeExpectedTotals) {
        return view(session, includeExpectedTotals, null);
    }

    private CashSessionView view(
            CashSession session,
            boolean includeExpectedTotals,
            CashReconciliationAttempt attempt) {
        BigDecimal expectedCash = null;
        BigDecimal availableCash = null;
        if (includeExpectedTotals && session.getStatus() == CashSessionStatus.ABIERTA) {
            availableCash = calculator.availableCash(session);
            expectedCash = availableCash;
        } else if (includeExpectedTotals) {
            expectedCash = session.getExpectedCash();
        }
        var retainedFund = includeExpectedTotals
                ? attempt == null ? session.getRetainedFund() : attempt.getDeclaredFund()
                : null;
        return new CashSessionView(
                session.getId(), session.getTerminalId(), session.getStatus(), session.getOpenedAt(),
                session.getOpeningFund(), expectedCash, availableCash,
                retainedFund,
                includeExpectedTotals
                        ? attempt == null ? session.getDiscrepancy() : attempt.getDiscrepancy()
                        : null,
                session.getClosedAt(),
                attempt == null ? null : attempt.getAttemptNumber(),
                attempt != null && attempt.closedSession());
    }

    private CashSessionView replayedAttemptView(
            CashSession session,
            boolean includeExpectedTotals,
            CashReconciliationAttempt attempt) {
        var replayStatus = attempt.closedSession()
                ? CashSessionStatus.CERRADA
                : CashSessionStatus.ABIERTA;
        return new CashSessionView(
                session.getId(),
                session.getTerminalId(),
                replayStatus,
                session.getOpenedAt(),
                session.getOpeningFund(),
                includeExpectedTotals ? attempt.getExpectedCash() : null,
                includeExpectedTotals && !attempt.closedSession()
                        ? attempt.getExpectedCash()
                        : null,
                includeExpectedTotals ? attempt.getDeclaredFund() : null,
                includeExpectedTotals ? attempt.getDiscrepancy() : null,
                attempt.closedSession() ? session.getClosedAt() : null,
                attempt.getAttemptNumber(),
                attempt.closedSession());
    }

    private CashStoreConfig config(CashSession session) {
        return config(session.getStoreId());
    }

    private CashStoreConfig config(UUID storeId) {
        return configs.findById(storeId).orElseGet(() -> new CashStoreConfig(storeId));
    }

    private BigDecimal positiveAmount(BigDecimal value) {
        var amount = Money.euros(value);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("importe debe ser positivo");
        }
        return amount;
    }

    private BigDecimal nonNegativeAmount(BigDecimal value) {
        var amount = Money.euros(value == null ? BigDecimal.ZERO : value);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("importe no puede ser negativo");
        }
        return amount;
    }

    private String closeWithdrawalHash(
            CashSession session,
            BigDecimal amount,
            String comment,
            List<CashDenominationCommand> denominations) {
        var normalizedComment = comment == null || comment.isBlank() ? "" : comment.trim();
        var normalizedDenominations = (denominations == null
                ? List.<CashDenominationCommand>of()
                : denominations)
                .stream()
                .sorted(Comparator
                        .comparing((CashDenominationCommand command) -> Money.euros(command.denomination()))
                        .thenComparingInt(CashDenominationCommand::quantity))
                .map(command -> Money.euros(command.denomination()).toPlainString()
                        + "x" + command.quantity())
                .collect(Collectors.joining(","));
        var canonicalPayload = session.getStoreId()
                + "|" + session.getTerminalId()
                + "|" + session.getId()
                + "|" + amount.toPlainString()
                + "|" + normalizedComment
                + "|" + normalizedDenominations;
        return sha256(canonicalPayload);
    }

    private String closeAttemptHash(
            CashCloseOperation operation,
            BigDecimal retainedFund,
            List<CashDenominationCommand> denominations) {
        var normalizedDenominations = (denominations == null
                ? List.<CashDenominationCommand>of()
                : denominations)
                .stream()
                .sorted(Comparator
                        .comparing((CashDenominationCommand command) -> Money.euros(command.denomination()))
                        .thenComparingInt(CashDenominationCommand::quantity))
                .map(command -> Money.euros(command.denomination()).toPlainString()
                        + "x" + command.quantity())
                .collect(Collectors.joining(","));
        return sha256(operation.getId()
                + "|" + Money.euros(retainedFund).toPlainString()
                + "|" + normalizedDenominations);
    }

    private String sha256(String canonicalPayload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private boolean isLateClose(CashSession session, Instant closedAt) {
        var zone = clock.getZone();
        return !LocalDate.ofInstant(session.getOpenedAt(), zone)
                .equals(LocalDate.ofInstant(closedAt, zone));
    }

    private void validateDenominations(
            BigDecimal amount,
            List<CashDenominationCommand> denominations,
            boolean required) {
        var commands = denominations == null ? List.<CashDenominationCommand>of() : denominations;
        if (required && commands.isEmpty()) {
            throw new IllegalArgumentException("El desglose de denominaciones es obligatorio");
        }
        if (commands.isEmpty()) {
            return;
        }
        for (var command : commands) {
            if (command == null || command.quantity() <= 0) {
                throw new IllegalArgumentException("Las cantidades de denominaciones deben ser positivas");
            }
            var denomination = Money.euros(command.denomination());
            if (!CashDenomination.valuesInEuroOrder().contains(denomination)) {
                throw new IllegalArgumentException("La denominacion indicada no es valida");
            }
        }
        var total = commands.stream()
                .map(command -> Money.euros(command.denomination())
                        .multiply(BigDecimal.valueOf(command.quantity())))
                .reduce(Money.euros("0"), BigDecimal::add);
        if (Money.euros(total).compareTo(amount) != 0) {
            throw new IllegalArgumentException("El desglose de denominaciones no coincide con el importe");
        }
    }

    private void addDenominations(CashMovement movement, List<CashDenominationCommand> denominations) {
        if (denominations == null) {
            return;
        }
        for (var denomination : denominations) {
            movement.addDenomination(denomination.denomination(), denomination.quantity());
        }
    }
}
