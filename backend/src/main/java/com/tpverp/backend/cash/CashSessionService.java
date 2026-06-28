package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashSessionService {

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CashStoreConfigRepository configs;
    private final TerminalRepository terminals;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;
    private final CashAmountCalculator calculator;
    private final Clock clock;

    public CashSessionService(
            CashSessionRepository sessions,
            CashMovementRepository movements,
            CashStoreConfigRepository configs,
            TerminalRepository terminals,
            CurrentOrganization organization,
            CashPermissionService permissions,
            CashAmountCalculator calculator,
            Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.configs = configs;
        this.terminals = terminals;
        this.organization = organization;
        this.permissions = permissions;
        this.calculator = calculator;
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
        var terminal = validateTerminal(terminalId);
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
        var session = CashSession.open(
                terminal.getTienda().getId(), terminal.getId(), user.getId(), Instant.now(clock),
                calculator.nextOpeningFund(terminal.getId()));
        return view(sessions.save(session), permissions.canSeeExpectedTotals(authentication));
    }

    // Records a manual entry in an open session after validating accounting authorization.
    @Transactional
    public CashMovementView entry(UUID terminalId, CashEntryRequest request, Authentication authentication) {
        permissions.requireSalesPermission(authentication);
        validateTerminal(terminalId);
        if (request.comment() == null || request.comment().isBlank()) {
            throw new IllegalArgumentException("El comentario es obligatorio");
        }
        var session = openSession(terminalId);
        var amount = positiveAmount(request.amount());
        validateDenominations(amount, request.denominations(), config(session).isRequireEntryBreakdown());
        var user = organization.currentUser(authentication);
        var authorizer = permissions.requireAuthorizer(request.authorizerUsername(), request.authorizerPassword());
        var movement = CashMovement.sessionMovement(
                session.getStoreId(), session.getTerminalId(), session, CashMovementType.ENTRADA,
                amount, Instant.now(clock), user.getId(), authorizer.getId(), request.comment(), null, null);
        addDenominations(movement, request.denominations());
        return CashMovementView.from(movements.save(movement));
    }

    // Records a cash withdrawal in an open session.
    @Transactional
    public CashMovementView withdrawal(UUID terminalId, CashWithdrawalRequest request, Authentication authentication) {
        permissions.requireSalesPermission(authentication);
        validateTerminal(terminalId);
        var session = openSession(terminalId);
        var amount = positiveAmount(request.amount());
        validateDenominations(amount, request.denominations(), config(session).isRequireWithdrawalBreakdown());
        if (amount.compareTo(calculator.availableCash(session)) > 0) {
            throw new IllegalArgumentException("La retirada supera el efectivo disponible");
        }
        var movement = CashMovement.sessionMovement(
                session.getStoreId(), session.getTerminalId(), session, CashMovementType.RETIRADA,
                amount, Instant.now(clock), organization.currentUser(authentication).getId(),
                null, request.comment(), null, null);
        addDenominations(movement, request.denominations());
        return CashMovementView.from(movements.save(movement));
    }

    // Cierra una sesion mediante arqueo ciego, conservando abiertos los primeros descuadres fuera de tolerancia.
    @Transactional
    public CashSessionView close(UUID terminalId, CashCloseRequest request, Authentication authentication) {
        permissions.requireSalesPermission(authentication);
        validateTerminal(terminalId);
        var session = openSession(terminalId);
        var cashConfig = config(session);
        var user = organization.currentUser(authentication);
        var finalWithdrawal = nonNegativeAmount(request.finalWithdrawalAmount());
        validateDenominations(
                finalWithdrawal,
                request.finalWithdrawalDenominations(),
                finalWithdrawal.signum() > 0 && cashConfig.isRequireWithdrawalBreakdown());
        var availableCash = calculator.availableCash(session);
        if (finalWithdrawal.compareTo(availableCash) > 0) {
            throw new IllegalArgumentException("La retirada de cierre supera el efectivo disponible");
        }
        if (finalWithdrawal.signum() > 0) {
            var movement = CashMovement.sessionMovement(
                    session.getStoreId(), session.getTerminalId(), session, CashMovementType.RETIRADA_CIERRE,
                    finalWithdrawal, Instant.now(clock), user.getId(), null,
                    request.finalWithdrawalComment(), null, null);
            addDenominations(movement, request.finalWithdrawalDenominations());
            movements.save(movement);
        }
        var expectedCash = availableCash.subtract(finalWithdrawal);
        var retainedFund = nonNegativeAmount(request.retainedFund());
        validateDenominations(retainedFund, request.retainedFundDenominations(), cashConfig.isRequireClosingBreakdown());
        var attempt = session.registerAttempt(
                user.getId(), Instant.now(clock), retainedFund, expectedCash, cashConfig.getDiscrepancyTolerance());
        if (attempt.closedSession() && isLateClose(session, attempt.getCreatedAt())) {
            session.markLateClosing();
        }
        sessions.save(session);
        return view(session, permissions.canSeeExpectedTotals(authentication), attempt);
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

    private CashSession openSession(UUID terminalId) {
        return sessions.findByTerminalIdAndStatus(terminalId, CashSessionStatus.ABIERTA)
                .orElseThrow(() -> new IllegalStateException("No hay una sesion de caja abierta"));
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
                attempt == null ? session.getDiscrepancy() : attempt.getDiscrepancy(),
                session.getClosedAt(),
                attempt == null ? null : attempt.getAttemptNumber(),
                attempt != null && attempt.closedSession());
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
