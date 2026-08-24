package com.tpverp.backend.cash;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.document.template.OperationalDocumentJasperRenderer;
import com.tpverp.backend.document.template.RenderedDocumentView;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccountRepository;
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
    private final UserAccountRepository users;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;
    private OperationalDocumentJasperRenderer printing;

    public CashReceiptService(
            CashSessionRepository sessions,
            CashMovementRepository movements,
            TerminalRepository terminals,
            UserAccountRepository users,
            CurrentOrganization organization,
            CashPermissionService permissions) {
        this.sessions = sessions;
        this.movements = movements;
        this.terminals = terminals;
        this.users = users;
        this.organization = organization;
        this.permissions = permissions;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPrinting(OperationalDocumentJasperRenderer printing) {
        this.printing = printing;
    }

    @Transactional(readOnly = true)
    public RenderedDocumentView withdrawalPrintDocument(
            UUID movementId, Authentication authentication) {
        if (printing == null) {
            throw new IllegalStateException("cash_receipt_printing_unavailable");
        }
        var receipt = withdrawalReceipt(movementId, authentication);
        var data = printing.mapper().createObjectNode();
        var document = data.putObject("document");
        document.put("displayNumber", receipt.movementId().toString());
        document.put("issueDate", receipt.createdAt().toString());
        var issuer = data.putObject("issuer");
        issuer.put("headerPrimaryName", organization.currentStore().getNombreEfectivo());
        issuer.put("legalName", organization.currentCompany().getRazonSocial());
        issuer.put("details", organization.currentCompany().getRazonSocial());
        var lines = data.putArray("lines");
        line(lines, "Operador", receipt.userName());
        line(lines, "Terminal", receipt.terminalName());
        line(lines, "Importe", receipt.amount());
        line(lines, "Autorizador", receipt.authorizerName());
        line(lines, "Comentario", receipt.comment());
        receipt.denominations().forEach(value -> line(lines,
                value.denomination() + " €", value.quantity()));
        return printing.render(DocumentTemplateType.RETIRADA_CAJA,
                DocumentTemplateFormat.TICKET_80, data,
                "retirada-caja-" + receipt.movementId() + ".pdf");
    }

    private static void line(com.fasterxml.jackson.databind.node.ArrayNode lines,
            String label, Object value) {
        var node = lines.addObject();
        node.put("label", label);
        if (value == null) node.putNull("value");
        else node.put("value", value.toString());
    }

    // Returns printable withdrawal data without print side effects.
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
                        .orElseThrow(() -> new IllegalArgumentException("UserSession de caja no encontrada"));
        var terminal = terminals.findByIdAndTiendaId(movement.getTerminalId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
        var userName = users.findByIdAndTiendaId(movement.getUserId(), store.getId())
                .map(user -> user.getNombre())
                .orElse(movement.getUserId().toString());
        var authorizerName = movement.getAuthorizerUserId() == null
                ? null
                : users.findByIdAndTiendaId(movement.getAuthorizerUserId(), store.getId())
                        .map(user -> user.getNombre())
                        .orElse(movement.getAuthorizerUserId().toString());
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
                EMPTY_SIGNATURE_LABEL,
                authorizerName,
                movement.getComment());
    }

    // Returns printable close data while filtering theoretical amounts by permission.
    @Transactional(readOnly = true)
    public CashReceiptView closeReceipt(UUID sessionId, Authentication authentication) {
        permissions.requireCashStatusPermission(authentication);
        var store = organization.currentStore();
        var session = sessions.findById(sessionId)
                .filter(found -> found.getStoreId().equals(store.getId()))
                .orElseThrow(() -> new IllegalArgumentException("UserSession de caja no encontrada"));
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
        var includeExpectedTotals = permissions.canSeeExpectedTotals(authentication);
        return new CashReceiptView(
                null,
                session.getId(),
                session.getTerminalId(),
                terminal.getNombre(),
                session.getClosedAt(),
                userName,
                null,
                List.of(),
                includeExpectedTotals ? session.getRetainedFund() : null,
                includeExpectedTotals ? session.getDiscrepancy() : null,
                includeExpectedTotals ? session.getExpectedCash() : null,
                EMPTY_SIGNATURE_LABEL,
                EMPTY_SIGNATURE_LABEL,
                null,
                null);
    }

    private List<CashDenominationCommand> denominations(CashMovement movement) {
        return movement.getDenominations().stream()
                .map(denomination -> new CashDenominationCommand(
                        denomination.getDenomination(), denomination.getQuantity()))
                .toList();
    }
}
