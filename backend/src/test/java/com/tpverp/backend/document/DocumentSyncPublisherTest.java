package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Callback contract only; transaction durability and database locking are tested with PostgreSQL. */
class DocumentSyncPublisherTest {
    private final EntityManager entities = mock(EntityManager.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final DocumentSyncRevisionRepository revisions = mock(DocumentSyncRevisionRepository.class);
    private final DocumentSyncPayloadFactory payloads = mock(DocumentSyncPayloadFactory.class);
    private final SyncOutboxService outbox = mock(SyncOutboxService.class);
    private final DocumentSyncPublisher publisher = new DocumentSyncPublisher(entities, stores, revisions, payloads, outbox);
    private final UUID companyId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID terminalId = UUID.randomUUID();

    @BeforeEach
    void configurePayloads() {
        when(payloads.create(any(), anyLong())).thenAnswer(call -> Map.of(
                "estado", ((CommercialDocument) call.getArgument(0)).getEstado().name(),
                "sourceRevision", (long) call.getArgument(1)));
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void requiresAnActualWritableTransactionWithSynchronization() {
        CommercialDocument document = mock(CommercialDocument.class);
        assertThatThrownBy(() -> publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaccion de escritura");
        assertThatThrownBy(() -> publisher.scheduleIfUnpublished(companyId, document, terminalId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaccion de escritura");
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> publisher.scheduleIfUnpublished(companyId, document, terminalId))
                .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> publisher.scheduleIfUnpublished(companyId, document, terminalId))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(entities, stores, revisions, payloads, outbox);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coalescesInitialSnapshotsAndReadsFinalStateOnlyAfterClaim(boolean cancelled) {
        begin();
        var document = managed(UUID.randomUUID());
        when(document.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(revisions.tryClaimInitialRevision(document.getId())).thenReturn(true);
        doAnswer(call -> {
            when(document.getEstado()).thenReturn(cancelled ? DocumentStatus.ANULADO : DocumentStatus.PAGADO);
            return null;
        }).when(entities).refresh(document);
        publisher.scheduleIfUnpublished(companyId, document, terminalId);
        publisher.scheduleIfUnpublished(companyId, document, terminalId);
        verifyNoInteractions(entities, revisions, payloads, outbox);

        callback().beforeCommit(false);

        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        var order = inOrder(entities, revisions, payloads, outbox);
        order.verify(entities).flush();
        order.verify(revisions).tryClaimInitialRevision(document.getId());
        order.verify(entities).find(CommercialDocument.class, document.getId());
        order.verify(entities).refresh(document);
        order.verify(payloads).create(document, 1L);
        order.verify(outbox).enqueue(command.capture());
        order.verify(entities).flush();
        verify(revisions, never()).nextRevision(any());
        assertThat(command.getValue().operation()).isEqualTo(cancelled ? SyncOperation.ANULAR : SyncOperation.ACTUALIZAR);
        assertThat(command.getValue().payload()).containsEntry("sourceRevision", 1L)
                .containsEntry("estado", cancelled ? "ANULADO" : "PAGADO");
    }

    @Test
    void previouslyPublishedDocumentDoesNotAllocateAnotherRevisionOrBuildAnotherSnapshot() {
        begin();
        var document = managed(UUID.randomUUID());
        when(revisions.tryClaimInitialRevision(document.getId())).thenReturn(false);
        publisher.scheduleIfUnpublished(companyId, document, terminalId);

        callback().beforeCommit(false);

        verify(revisions).tryClaimInitialRevision(document.getId());
        verify(revisions, never()).nextRevision(any());
        verify(entities, never()).find(CommercialDocument.class, document.getId());
        verify(entities, never()).refresh(any());
        verifyNoInteractions(payloads, outbox);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void operationalRequestTakesPrecedenceRegardlessOfSchedulingOrder(boolean operationalFirst) {
        begin();
        var document = managed(UUID.randomUUID());
        UUID recoveryTerminal = UUID.randomUUID();
        when(revisions.nextRevision(document.getId())).thenReturn(7L);
        if (operationalFirst) publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);
        publisher.scheduleIfUnpublished(companyId, document, recoveryTerminal);
        if (!operationalFirst) publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);

        callback().beforeCommit(false);

        verify(revisions).nextRevision(document.getId());
        verify(revisions, never()).tryClaimInitialRevision(any());
        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(command.capture());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CONFIRMAR);
        assertThat(command.getValue().terminalId()).isEqualTo(terminalId);
        assertThat(command.getValue().payload()).containsEntry("sourceRevision", 7L);
    }

    @Test
    void rollbackBeforeCommitDiscardsInitialPublicationWithoutClaimOrOutbox() {
        begin();
        var document = managed(UUID.randomUUID());
        publisher.scheduleIfUnpublished(companyId, document, terminalId);

        callback().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(entities, revisions, payloads, outbox);
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void coalescesConfirmationAndLaterPaymentIntoOneFinalSnapshot() {
        begin();
        var document = invoice();
        var relations = mock(DocumentRelationRepository.class);
        when(relations.findOutgoingForSync(document.getId(), storeId)).thenReturn(List.of());
        var realPayloadPublisher = new DocumentSyncPublisher(entities, stores, revisions,
                new DocumentSyncPayloadFactory(relations, org.mockito.Mockito.mock(DocumentAttributionResolver.class)), outbox);
        allowStore();
        when(entities.find(CommercialDocument.class, document.getId())).thenReturn(document);
        when(revisions.nextRevision(document.getId())).thenReturn(8L);
        realPayloadPublisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        verifyNoInteractions(entities, revisions, relations, outbox);

        var method = new PaymentMethod(companyId, "EFECTIVO", true);
        document.addPayment(new DocumentPayment(document, method, 1, new BigDecimal("10.00"), true,
                null, null, null, null, Instant.parse("2026-09-10T10:01:00Z")));
        document.updatePaymentStatus();
        UUID laterTerminal = UUID.randomUUID();
        realPayloadPublisher.schedule(companyId, document, laterTerminal, SyncOperation.ACTUALIZAR);
        callback().beforeCommit(false);

        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(command.capture());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CONFIRMAR);
        assertThat(command.getValue().terminalId()).isEqualTo(laterTerminal);
        assertThat(command.getValue().payload()).containsEntry("estado", "PAGADO")
                .containsEntry("sourceRevision", 8L).containsEntry("total", "10.00");
        assertThat((List<?>) command.getValue().payload().get("pagos")).hasSize(1);
        verify(revisions).nextRevision(document.getId());
        verify(entities, times(2)).flush();
    }

    @Test
    void refreshesAfterAcquiringRevisionAndUsesTheRefreshedCancellationState() {
        begin();
        var document = managed(UUID.randomUUID());
        when(document.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(revisions.nextRevision(document.getId())).thenReturn(3L);
        doAnswer(call -> {
            when(document.getEstado()).thenReturn(DocumentStatus.ANULADO);
            return null;
        }).when(entities).refresh(document);
        publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);
        publisher.schedule(companyId, document, terminalId, SyncOperation.ACTUALIZAR);

        callback().beforeCommit(false);

        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        var order = inOrder(entities, revisions, payloads, outbox);
        order.verify(entities).flush();
        order.verify(revisions).nextRevision(document.getId());
        order.verify(entities).find(CommercialDocument.class, document.getId());
        order.verify(entities).refresh(document);
        order.verify(payloads).create(document, 3L);
        order.verify(outbox).enqueue(command.capture());
        order.verify(entities).flush();
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.ANULAR);
        assertThat(command.getValue().payload()).containsEntry("estado", "ANULADO");
    }

    @Test
    void acquiresMultipleDocumentRevisionsInStableUuidOrder() {
        begin();
        var first = managed(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var second = managed(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        publisher.schedule(companyId, second, terminalId, SyncOperation.ACTUALIZAR);
        publisher.schedule(companyId, first, terminalId, SyncOperation.ACTUALIZAR);
        callback().beforeCommit(false);

        var order = inOrder(revisions);
        order.verify(revisions).nextRevision(first.getId());
        order.verify(revisions).nextRevision(second.getId());
        verify(outbox, times(2)).enqueue(any());
        verify(entities, times(2)).flush();
    }

    @Test
    void rollbackBeforeCommitDiscardsTheBufferWithoutAllocatingRevisionOrOutbox() {
        begin();
        var document = mock(CommercialDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getTiendaId()).thenReturn(storeId);
        publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);
        callback().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(entities, stores, revisions, payloads, outbox);
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void suspendsOuterBufferAndRestoresItAfterAnIndependentCommit() {
        begin();
        var outerDocument = managed(UUID.randomUUID());
        var innerDocument = managed(UUID.randomUUID());
        publisher.schedule(companyId, outerDocument, terminalId, SyncOperation.CONFIRMAR);
        TransactionSynchronization outer = callback();
        outer.suspend();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();

        publisher.schedule(companyId, innerDocument, terminalId, SyncOperation.ACTUALIZAR);
        TransactionSynchronization inner = callback();
        assertThat(inner).isNotSameAs(outer);
        inner.beforeCommit(false);
        inner.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.registerSynchronization(outer);
        outer.resume();
        outer.beforeCommit(false);

        var commands = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox, times(2)).enqueue(commands.capture());
        assertThat(commands.getAllValues()).extracting(SyncOutboundEventCommand::entityId)
                .containsExactly(innerDocument.getId(), outerDocument.getId());
        assertThat(commands.getAllValues()).extracting(SyncOutboundEventCommand::operation)
                .containsExactly(SyncOperation.ACTUALIZAR, SyncOperation.CONFIRMAR);
        outer.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void independentRollbackDoesNotDiscardSuspendedOuterRequest() {
        begin();
        var document = managed(UUID.randomUUID());
        publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);
        TransactionSynchronization outer = callback();
        outer.suspend();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        publisher.schedule(companyId, document, terminalId, SyncOperation.ACTUALIZAR);
        callback().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(revisions, payloads, outbox);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.registerSynchronization(outer);
        outer.resume();
        outer.beforeCommit(false);

        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(command.capture());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CONFIRMAR);
    }

    @Test
    void rejectsWrongCompanyBeforeCounterAllocationAndOutbox() {
        begin();
        var document = managed(UUID.randomUUID());
        publisher.schedule(UUID.randomUUID(), document, terminalId, SyncOperation.CONFIRMAR);

        assertThatThrownBy(() -> callback().beforeCommit(false)).isInstanceOf(IllegalStateException.class)
                .hasMessage("La empresa del documento no coincide con su tienda");
        verifyNoInteractions(revisions, payloads, outbox);
    }

    @Test
    void refusesARefreshedDraftBeforeBuildingPayloadOrOutbox() {
        begin();
        var document = managed(UUID.randomUUID());
        when(document.getEstado()).thenReturn(DocumentStatus.BORRADOR);
        publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);

        assertThatThrownBy(() -> callback().beforeCommit(false)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Un documento borrador no puede publicarse como confirmado");
        verify(entities).refresh(document);
        verifyNoInteractions(payloads, outbox);
    }

    @Test
    void flushFailurePreventsSnapshotAndOutbox() {
        begin();
        var document = managed(UUID.randomUUID());
        publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);
        doThrow(new IllegalStateException("simulated database failure")).when(entities).flush();

        assertThatThrownBy(() -> callback().beforeCommit(false)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(revisions, payloads, outbox);
    }

    @Test
    void rejectsFurtherSchedulesOnceSnapshotPublicationHasStarted() {
        begin();
        var document = managed(UUID.randomUUID());
        publisher.schedule(companyId, document, terminalId, SyncOperation.CONFIRMAR);
        callback().beforeCommit(false);

        assertThatThrownBy(() -> publisher.schedule(companyId, document, terminalId, SyncOperation.ACTUALIZAR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ya ha comenzado");
        verify(outbox).enqueue(any());
    }

    private void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.initSynchronization();
    }

    private TransactionSynchronization callback() {
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        return TransactionSynchronizationManager.getSynchronizations().getFirst();
    }

    private void allowStore() {
        var company = mock(Company.class);
        when(company.getId()).thenReturn(companyId);
        var store = mock(Store.class);
        when(store.getEmpresa()).thenReturn(company);
        when(stores.findWithCompanyById(storeId)).thenReturn(Optional.of(store));
    }

    private CommercialDocument managed(UUID id) {
        allowStore();
        var document = mock(CommercialDocument.class);
        when(document.getId()).thenReturn(id);
        when(document.getTiendaId()).thenReturn(storeId);
        when(document.getEstado()).thenReturn(DocumentStatus.PAGADO);
        when(entities.find(CommercialDocument.class, id)).thenReturn(document);
        when(revisions.nextRevision(id)).thenReturn(1L);
        return document;
    }

    private CommercialDocument invoice() {
        var document = new CommercialDocument(storeId, UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 9, 10), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, UUID.randomUUID(), 1, BigDecimal.ONE, "P1", "Product", null,
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.confirm("FV-1", UUID.randomUUID(), Instant.parse("2026-09-10T10:00:00Z"), false);
        return document;
    }
}
