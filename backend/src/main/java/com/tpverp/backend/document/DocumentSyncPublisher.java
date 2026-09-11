package com.tpverp.backend.document;

import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes one final snapshot per document at business-transaction commit, through the durable outbox. */
@Service
public class DocumentSyncPublisher {

    private static final Set<SyncOperation> OPERATIONS = Set.of(
            SyncOperation.CONFIRMAR, SyncOperation.ACTUALIZAR, SyncOperation.ANULAR);
    private final EntityManager entities;
    private final StoreRepository stores;
    private final DocumentSyncRevisionRepository revisions;
    private final DocumentSyncPayloadFactory payloads;
    private final SyncOutboxService outbox;
    private final Object resourceKey = new Object();

    public DocumentSyncPublisher(EntityManager entities, StoreRepository stores,
            DocumentSyncRevisionRepository revisions, DocumentSyncPayloadFactory payloads, SyncOutboxService outbox) {
        this.entities = entities;
        this.stores = stores;
        this.revisions = revisions;
        this.payloads = payloads;
        this.outbox = outbox;
    }

    public void schedule(UUID companyId, CommercialDocument document, UUID eventTerminalId, SyncOperation operation) {
        schedule(companyId, document, eventTerminalId, operation, false);
    }

    /** Enqueues the initial snapshot only when no publication revision has committed for this document. */
    public void scheduleIfUnpublished(UUID companyId, CommercialDocument document, UUID eventTerminalId) {
        schedule(companyId, document, eventTerminalId, SyncOperation.ACTUALIZAR, true);
    }

    private void schedule(UUID companyId, CommercialDocument document, UUID eventTerminalId,
            SyncOperation operation, boolean onlyIfUnpublished) {
        requireWritableTransaction();
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(document, "document");
        if (operation == null || !OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Operacion de sincronizacion documental no valida");
        }
        var request = new Pending(companyId, Objects.requireNonNull(document.getTiendaId(), "storeId"),
                Objects.requireNonNull(document.getId(), "documentId"), eventTerminalId,
                operation == SyncOperation.CONFIRMAR, onlyIfUnpublished);
        var buffer = (Buffer) TransactionSynchronizationManager.getResource(resourceKey);
        if (buffer == null) {
            buffer = new Buffer();
            TransactionSynchronizationManager.bindResource(resourceKey, buffer);
            TransactionSynchronizationManager.registerSynchronization(buffer);
        }
        buffer.add(request);
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("La sincronizacion documental requiere una transaccion de escritura activa");
        }
    }

    private final class Buffer implements TransactionSynchronization {
        private final Map<UUID, Pending> pending = new TreeMap<>();
        private boolean publishing;

        private void add(Pending request) {
            if (publishing) throw new IllegalStateException("La publicacion documental ya ha comenzado");
            Pending previous = pending.get(request.documentId());
            if (previous != null) {
                if (!previous.companyId().equals(request.companyId()) || !previous.storeId().equals(request.storeId())) {
                    throw new IllegalStateException("La procedencia documental no coincide");
                }
                UUID terminalId = request.onlyIfUnpublished() && !previous.onlyIfUnpublished()
                        ? previous.eventTerminalId() : request.eventTerminalId();
                request = new Pending(request.companyId(), request.storeId(), request.documentId(), terminalId,
                        previous.confirmationRequested() || request.confirmationRequested(),
                        previous.onlyIfUnpublished() && request.onlyIfUnpublished());
            }
            pending.put(request.documentId(), request);
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            requireWritableTransaction();
            if (readOnly) throw new IllegalStateException("La sincronizacion documental requiere una transaccion de escritura");
            if (publishing) throw new IllegalStateException("La publicacion documental ya ha comenzado");
            publishing = true;
            // Flush ALL pending business changes before refreshing any of the requested documents.
            entities.flush();
            for (Pending request : pending.values()) {
                var store = stores.findWithCompanyById(request.storeId())
                        .orElseThrow(() -> new IllegalStateException("Tienda documental no encontrada"));
                if (!request.companyId().equals(store.getEmpresa().getId())) {
                    throw new IllegalStateException("La empresa del documento no coincide con su tienda");
                }
                long revision;
                if (request.onlyIfUnpublished()) {
                    // The claim and outbox share this transaction; a concurrent publisher can win the claim.
                    if (!revisions.tryClaimInitialRevision(request.documentId())) continue;
                    revision = 1L;
                } else {
                    revision = revisions.nextRevision(request.documentId());
                }
                var document = entities.find(CommercialDocument.class, request.documentId());
                if (document == null) throw new IllegalStateException("Documento de sincronizacion no encontrado");
                // Acquiring the revision row can wait for a concurrent payment or relation commit.
                // Refresh after that wait, including the managed collections; never publish the earlier read.
                entities.refresh(document);
                if (!request.storeId().equals(document.getTiendaId())) {
                    throw new IllegalStateException("La tienda de origen del documento no coincide");
                }
                if (document.getEstado() == DocumentStatus.BORRADOR) {
                    throw new IllegalStateException("Un documento borrador no puede publicarse como confirmado");
                }
                SyncOperation operation = document.getEstado() == DocumentStatus.ANULADO
                        ? SyncOperation.ANULAR
                        : request.confirmationRequested() ? SyncOperation.CONFIRMAR : SyncOperation.ACTUALIZAR;
                outbox.enqueue(new SyncOutboundEventCommand(request.companyId(), request.storeId(),
                        request.eventTerminalId(), "DOCUMENTO", request.documentId(), operation,
                        payloads.create(document, revision)));
            }
            // Persist the outbox in this transaction; no network request is made here.
            entities.flush();
        }

        @Override
        public void suspend() {
            unbindOwnBuffer();
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(resourceKey, this);
        }

        @Override
        public void afterCompletion(int status) {
            unbindOwnBuffer();
            pending.clear();
        }

        private void unbindOwnBuffer() {
            if (TransactionSynchronizationManager.getResource(resourceKey) == this) {
                TransactionSynchronizationManager.unbindResource(resourceKey);
            }
        }
    }

    private record Pending(UUID companyId, UUID storeId, UUID documentId, UUID eventTerminalId,
            boolean confirmationRequested, boolean onlyIfUnpublished) { }
}
