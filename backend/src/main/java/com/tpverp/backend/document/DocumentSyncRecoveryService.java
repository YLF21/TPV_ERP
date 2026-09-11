package com.tpverp.backend.document;

import static com.tpverp.backend.document.DocumentSyncRecoveryApi.*;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-off DEV adaptation: no scheduler, job table, business mutation or automatic outbox flush. */
@Service
@ConditionalOnProperty(name = "tpv.sync.document-recovery-enabled", havingValue = "true")
public class DocumentSyncRecoveryService {
    private final boolean enabled;
    private final CurrentOrganization organization;
    private final DocumentSyncRecoveryRepository repository;
    private final CommercialDocumentRepository documents;
    private final DocumentSyncPayloadFactory payloads;
    private final DocumentSyncPublisher publisher;
    private final DocumentSyncRecoveryClient central;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public DocumentSyncRecoveryService(@Value("${tpv.sync.document-recovery-enabled:false}") boolean enabled,
            CurrentOrganization organization, DocumentSyncRecoveryRepository repository, CommercialDocumentRepository documents,
            DocumentSyncPayloadFactory payloads, DocumentSyncPublisher publisher, DocumentSyncRecoveryClient central,
            AuditService audit, Clock clock, PlatformTransactionManager transactions) {
        this.enabled = enabled; this.organization = organization; this.repository = repository; this.documents = documents;
        this.payloads = payloads; this.publisher = publisher; this.central = central; this.audit = audit; this.clock = clock;
        read = new TransactionTemplate(transactions); read.setReadOnly(true); read.setTimeout(30);
        write = new TransactionTemplate(transactions); write.setTimeout(30);
    }

    public Preview preview(PreviewRequest request, Authentication authentication) {
        authorize(authentication);
        if (request == null) throw DocumentSyncRecoveryException.invalid();
        if (request.afterId() != null && (request.scope() == null || request.scope().createdBefore() == null)) {
            throw DocumentSyncRecoveryException.invalid();
        }
        return read.execute(tx -> {
            var scope = scope(request.scope(), true);
            var ids = repository.candidates(scope, request.afterId());
            List<Row> rows = new ArrayList<>();
            for (UUID id : ids.stream().limit(100).toList()) {
                var document = documents.findById(id).orElseThrow(DocumentSyncRecoveryException::invalid);
                String problem = null;
                try { payloads.create(document, 1); }
                catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) { problem = "SNAPSHOT_INVALID"; }
                rows.add(new Row(id, document.getNumero(), document.getTipo().name(), document.getEstado().name(),
                        document.getFecha(), document.getTotal().toPlainString(), document.getMoneda(), problem));
            }
            boolean more = ids.size() > 100;
            return new Preview(scope, List.copyOf(rows), more ? rows.getLast().documentId() : null, more);
        });
    }

    public Prepared prepare(PrepareRequest request, Authentication authentication) {
        authorize(authentication);
        // The HTTP preflight must never hold a caller's DB transaction/connection open.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Ambient transaction not supported");
        if (request == null || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) {
            throw DocumentSyncRecoveryException.invalid();
        }
        var ids = ids(request.documentIds());
        Scope scope = read.execute(tx -> scope(request.scope(), false));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("companyId", scope.companyId()); details.put("storeId", scope.storeId());
        details.put("dateFrom", scope.dateFrom().toString()); details.put("dateTo", scope.dateTo().toString());
        details.put("createdBefore", scope.createdBefore().toString()); details.put("documentIds", ids);
        details.put("selectedCount", ids.size()); details.put("reason", request.reason().strip());
        try {
            var unpublished = read.execute(tx -> {
                scope(scope, false);
                List<UUID> selected = new ArrayList<>();
                for (UUID id : ids) {
                    validateDocument(scope, id);
                    if (!repository.published(id)) selected.add(id);
                }
                return List.copyOf(selected);
            });
            if (!unpublished.isEmpty()) {
                // Require a compatible receiver, and never reset a source sequence against an existing projection.
                var statuses = central.status(scope, unpublished.stream().map(id -> new Expectation(id, null, null)).toList());
                if (statuses.stream().anyMatch(row -> !row.status().equals("MISSING"))) {
                    throw DocumentSyncRecoveryException.conflict("DOCUMENT_RECOVERY_CENTRAL_SOURCE_CONFLICT");
                }
            }
            write.executeWithoutResult(tx -> {
                scope(scope, false); // revalidate authority/context and originals immediately before preparing
                for (UUID id : ids) {
                    var document = validateDocument(scope, id);
                    publisher.scheduleIfUnpublished(scope.companyId(), document, document.getTerminalOrigenId());
                }
                audit.record("DOCUMENT_SYNC_RECOVERY_PREPARE", AuditResult.EXITO, details);
            });
        } catch (RuntimeException exception) {
            var failure = new LinkedHashMap<>(details);
            failure.put("code", exception instanceof DocumentSyncRecoveryException ? exception.getMessage() : "DOCUMENT_RECOVERY_PREPARE_FAILED");
            try { write.executeWithoutResult(tx -> audit.record("DOCUMENT_SYNC_RECOVERY_PREPARE", AuditResult.FALLO, failure)); }
            catch (RuntimeException auditFailure) { exception.addSuppressed(auditFailure); }
            throw exception;
        }
        // Publisher callbacks have committed: no receipt is returned before the outbox is durable.
        return read.execute(tx -> new Prepared(scope, ids.stream().map(id -> repository.latestReceipt(scope, id)).toList(), "ENQUEUED"));
    }

    public Verified verify(VerifyRequest request, Authentication authentication) {
        authorize(authentication);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Ambient transaction not supported");
        if (request == null || request.documents() == null || request.documents().stream().anyMatch(Objects::isNull)) {
            throw DocumentSyncRecoveryException.invalid();
        }
        ids(request.documents().stream().map(Receipt::documentId).toList());
        Scope scope = read.execute(tx -> {
            var selected = scope(request.scope(), false);
            for (Receipt receipt : request.documents()) {
                revision(receipt.sourceRevision());
                if (receipt.eventId() == null || !repository.withinScope(selected, receipt.documentId())
                        || !repository.receiptMatches(selected, receipt)) {
                    throw DocumentSyncRecoveryException.conflict("DOCUMENT_RECOVERY_RECEIPT_MISMATCH");
                }
            }
            return selected;
        });
        var remote = central.status(scope, request.documents().stream()
                .map(receipt -> new Expectation(receipt.documentId(), receipt.eventId(), revision(receipt.sourceRevision()))).toList());
        List<VerifiedRow> rows = new ArrayList<>();
        for (int i = 0; i < remote.size(); i++) {
            var status = remote.get(i);
            var expected = request.documents().get(i);
            boolean projected = status.status().equals("PROJECTED") && status.requestedEventStatus().equals("PROJECTED")
                    && status.requestedRevisionRecorded() && status.currentRevision() != null
                    && status.currentRevision() >= revision(expected.sourceRevision());
            String result = projected ? Boolean.FALSE.equals(status.customerLinked()) ? "CUSTOMER_BINDING_MISSING" : "PROJECTED"
                    : status.status().equals("OTHER_INSTALLATION") ? "OTHER_INSTALLATION"
                    : status.requestedEventStatus().equals("PROJECTED") ? "REVISION_NOT_VERIFIED" : status.requestedEventStatus();
            rows.add(new VerifiedRow(expected.documentId(), expected.eventId(), expected.sourceRevision(), projected,
                    status.customerLinked(), result));
        }
        return new Verified(scope, rows.stream().allMatch(row -> row.status().equals("PROJECTED")), List.copyOf(rows));
    }

    private CommercialDocument validateDocument(Scope scope, UUID id) {
        if (!repository.withinScope(scope, id)) throw DocumentSyncRecoveryException.conflict("DOCUMENT_RECOVERY_SELECTION_CHANGED");
        var document = documents.findById(id).orElseThrow(DocumentSyncRecoveryException::invalid);
        try { payloads.create(document, 1); }
        catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw DocumentSyncRecoveryException.conflict("DOCUMENT_RECOVERY_SNAPSHOT_INVALID");
        }
        return document;
    }

    private Scope scope(Scope scope, boolean allowNewCutoff) {
        if (scope == null || scope.companyId() == null || scope.storeId() == null || scope.dateFrom() == null || scope.dateTo() == null
                || scope.dateFrom().getYear() < 1 || scope.dateTo().getYear() > 9998 || scope.dateFrom().isAfter(scope.dateTo())) {
            throw DocumentSyncRecoveryException.invalid();
        }
        var store = organization.currentStore();
        if (!store.getId().equals(scope.storeId()) || !organization.currentCompany().getId().equals(scope.companyId())) {
            throw new AccessDeniedException("DOCUMENT_RECOVERY_SCOPE_DENIED");
        }
        var cutoff = scope.createdBefore();
        if (cutoff == null && allowNewCutoff) cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (cutoff == null || cutoff.isAfter(clock.instant())) throw DocumentSyncRecoveryException.invalid();
        return new Scope(scope.companyId(), scope.storeId(), scope.dateFrom(), scope.dateTo(), cutoff);
    }
    private void authorize(Authentication auth) {
        if (!enabled) throw new DocumentSyncRecoveryException(HttpStatus.NOT_FOUND, "DOCUMENT_RECOVERY_DISABLED");
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || auth.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            throw new AccessDeniedException("DOCUMENT_RECOVERY_ADMIN_REQUIRED");
        }
        organization.currentUser(auth); // real, active local user (not just an unbound role string)
    }
    private static List<UUID> ids(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) throw DocumentSyncRecoveryException.invalid();
        return List.copyOf(ids);
    }
    private static long revision(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw DocumentSyncRecoveryException.invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw DocumentSyncRecoveryException.invalid(); }
    }
}
