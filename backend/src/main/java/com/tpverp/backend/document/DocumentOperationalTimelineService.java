package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentOperationalTimelineService {

    private static final EnumSet<CommercialDocumentType> PURCHASE_DOCUMENTS = EnumSet.of(
            CommercialDocumentType.ALBARAN_COMPRA,
            CommercialDocumentType.FACTURA_COMPRA,
            CommercialDocumentType.RECTIFICATIVA_COMPRA);

    private final CommercialDocumentRepository documents;
    private final DocumentOperationalEventRepository events;
    private final DocumentAttributionResolver attributions;
    private final UserAccountRepository users;
    private final TerminalRepository terminals;
    private final CurrentOrganization organization;

    public DocumentOperationalTimelineService(
            CommercialDocumentRepository documents,
            DocumentOperationalEventRepository events,
            DocumentAttributionResolver attributions,
            UserAccountRepository users,
            TerminalRepository terminals,
            CurrentOrganization organization) {
        this.documents = documents;
        this.events = events;
        this.attributions = attributions;
        this.users = users;
        this.terminals = terminals;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public DocumentOperationalTimelineView timeline(UUID documentId, Authentication authentication) {
        var storeId = organization.currentStore().getId();
        var document = documents.findByIdAndTiendaId(
                        Objects.requireNonNull(documentId, "documentId"), storeId)
                .orElseThrow(() -> new IllegalArgumentException("documento no encontrado"));
        requireReadPermission(document, authentication);

        var values = events.findAllByDocumentIdOrderByOccurredAtAscIdAsc(document.getId());
        var userIndex = users.findAllById(values.stream()
                        .map(DocumentOperationalEvent::getUserId)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(user -> user.getTienda() == null || storeId.equals(user.getTienda().getId()))
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        var terminalIndex = terminals.findAllById(values.stream()
                        .map(DocumentOperationalEvent::getTerminalId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(terminal -> storeId.equals(terminal.getTienda().getId()))
                .collect(Collectors.toMap(Terminal::getId, Function.identity()));
        var attribution = attributions.resolve(java.util.List.of(document)).get(document.getId());

        return new DocumentOperationalTimelineView(
                document.getId(), document.getTipo(), document.getEstado(), document.getNumero(),
                document.getFecha(), attribution.userId(), attribution.userName(),
                attribution.terminalId(), attribution.terminalName(),
                values.stream().map(event -> {
                    var user = userIndex.get(event.getUserId());
                    var terminal = terminalIndex.get(event.getTerminalId());
                    return new DocumentOperationalTimelineView.EventView(
                            event.getId(), event.getTipo(), event.getUserId(),
                            user == null ? "" : user.getUserName(), event.getTerminalId(),
                            terminal == null ? "" : terminal.getNombre(), event.getOccurredAt(),
                            event.getDatos());
                }).toList());
    }

    private static void requireReadPermission(
            CommercialDocument document,
            Authentication authentication) {
        if (PermissionChecks.hasRole(authentication, "ADMIN")) {
            return;
        }
        if (PURCHASE_DOCUMENTS.contains(document.getTipo())
                && PermissionChecks.hasPurchaseDocumentRead(authentication)) {
            return;
        }
        if (!PURCHASE_DOCUMENTS.contains(document.getTipo())
                && PermissionChecks.hasAuthority(authentication, "GESTION_VENTAS")) {
            return;
        }
        throw new AccessDeniedException("No tiene permiso para consultar la actividad del documento");
    }
}
