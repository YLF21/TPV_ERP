package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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

    // Devuelve el estado de caja filtrando importes teoricos segun permisos.
    @Transactional(readOnly = true)
    public CashSessionView status(UUID terminalId, Authentication authentication) {
        permissions.requireSalesPermission(authentication);
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
        if (!hasPreviousClosed && betweenSessions.isEmpty()) {
            throw new IllegalStateException("La primera apertura requiere una entrada entre sesiones");
        }
        var user = organization.currentUser(authentication);
        var session = CashSession.open(
                terminal.getTienda().getId(), terminal.getId(), user.getId(), Instant.now(clock),
                calculator.nextOpeningFund(terminal.getId()));
        return view(sessions.save(session), permissions.canSeeExpectedTotals(authentication));
    }

    // Registra una entrada manual en una sesion abierta validando autorizador contable.
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

    // Registra una retirada de efectivo en una sesion abierta.
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

    // Registra efectivo preparado entre sesiones cuando no hay caja abierta.
    @Transactional
    public CashMovementView betweenSessions(
            UUID terminalId, CashWithdrawalRequest request, Authentication authentication) {
        permissions.requireAccountingPermission(authentication);
        var terminal = validateTerminal(terminalId);
        if (sessions.findByTerminalIdAndStatus(terminal.getId(), CashSessionStatus.ABIERTA).isPresent()) {
            throw new IllegalStateException("No se permiten movimientos entre sesiones con caja abierta");
        }
        var amount = positiveAmount(request.amount());
        validateDenominations(amount, request.denominations(), config(terminal.getTienda().getId()).isRequireEntryBreakdown());
        var movement = CashMovement.betweenSessions(
                terminal.getTienda().getId(), terminal.getId(), amount, Instant.now(clock),
                organization.currentUser(authentication).getId(), null, request.comment());
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
        BigDecimal expectedCash = null;
        BigDecimal availableCash = null;
        if (includeExpectedTotals && session.getStatus() == CashSessionStatus.ABIERTA) {
            availableCash = calculator.availableCash(session);
            expectedCash = availableCash;
        } else if (includeExpectedTotals) {
            expectedCash = session.getExpectedCash();
        }
        return new CashSessionView(
                session.getId(), session.getTerminalId(), session.getStatus(), session.getOpenedAt(),
                session.getOpeningFund(), expectedCash, availableCash);
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
