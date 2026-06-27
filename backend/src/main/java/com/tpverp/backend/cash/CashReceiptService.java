package com.tpverp.backend.cash;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UsuarioRepository;
import com.tpverp.backend.terminal.TerminalRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashReceiptService {

    private static final String EMPTY_SIGNATURE_LABEL = "";
    private static final Set<CashMovementType> WITHDRAWAL_RECEIPT_TYPES = EnumSet.of(
            CashMovementType.RETIRADA,
            CashMovementType.RETIRADA_CIERRE,
            CashMovementType.RETIRADA_ENTRE_SESIONES);

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final TerminalRepository terminals;
    private final UsuarioRepository users;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;

    public CashReceiptService(
            CashSessionRepository sessions,
            CashMovementRepository movements,
            TerminalRepository terminals,
            UsuarioRepository users,
            CurrentOrganization organization,
            CashPermissionService permissions) {
        this.sessions = sessions;
        this.movements = movements;
        this.terminals = terminals;
        this.users = users;
        this.organization = organization;
        this.permissions = permissions;
    }

    // Devuelve los datos imprimibles de una retirada sin efectos de impresion.
    @Transactional(readOnly = true)
    public CashReceiptView withdrawalReceipt(UUID movementId, Authentication authentication) {
        permissions.requireCashStatusPermission(authentication);
        var store = organization.currentStore();
        var movement = movements.findById(movementId)
                .orElseThrow(() -> new IllegalArgumentException("Movimiento de caja no encontrado"));
        if (!WITHDRAWAL_RECEIPT_TYPES.contains(movement.getType())) {
            throw new IllegalArgumentException("El movimiento no es una retirada de caja");
        }
        var session = movement.getSessionId() == null
                ? null
                : sessions.findById(movement.getSessionId())
                        .orElseThrow(() -> new IllegalArgumentException("Sesion de caja no encontrada"));
        var terminal = terminals.findByIdAndTiendaId(movement.getTerminalId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
        var userName = users.findByIdAndTiendaId(movement.getUserId(), store.getId())
                .map(user -> user.getNombre())
                .orElse(movement.getUserId().toString());
        return new CashReceiptView(
                movement.getId(),
                session == null ? null : session.getId(),
                movement.getTerminalId(),
                terminal.getNombre(),
                movement.getCreatedAt(),
                userName,
                movement.getAmount(),
                denominations(movement),
                null,
                null,
                null,
                EMPTY_SIGNATURE_LABEL,
                EMPTY_SIGNATURE_LABEL);
    }

    // Devuelve los datos imprimibles de cierre filtrando importes teoricos por permiso.
    @Transactional(readOnly = true)
    public CashReceiptView closeReceipt(UUID sessionId, Authentication authentication) {
        permissions.requireCashStatusPermission(authentication);
        var store = organization.currentStore();
        var session = sessions.findById(sessionId)
                .filter(found -> found.getStoreId().equals(store.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Sesion de caja no encontrada"));
        if (session.getStatus() != CashSessionStatus.CERRADA) {
            throw new IllegalStateException("La sesion de caja sigue abierta");
        }
        var terminal = terminals.findByIdAndTiendaId(session.getTerminalId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
        var userId = session.getClosingUserId() == null
                ? organization.currentUser(authentication).getId()
                : session.getClosingUserId();
        var userName = users.findByIdAndTiendaId(userId, store.getId())
                .map(user -> user.getNombre())
                .orElse(userId.toString());
        return new CashReceiptView(
                null,
                session.getId(),
                session.getTerminalId(),
                terminal.getNombre(),
                session.getClosedAt(),
                userName,
                null,
                List.of(),
                session.getRetainedFund(),
                session.getDiscrepancy(),
                permissions.canSeeExpectedTotals(authentication) ? session.getExpectedCash() : null,
                EMPTY_SIGNATURE_LABEL,
                EMPTY_SIGNATURE_LABEL);
    }

    private List<CashDenominationCommand> denominations(CashMovement movement) {
        return movement.getDenominations().stream()
                .map(denomination -> new CashDenominationCommand(
                        denomination.getDenomination(), denomination.getQuantity()))
                .toList();
    }
}
