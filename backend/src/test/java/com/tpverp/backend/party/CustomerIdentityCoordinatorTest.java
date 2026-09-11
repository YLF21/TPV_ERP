package com.tpverp.backend.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class CustomerIdentityCoordinatorTest {
    private final CustomerIdentityOperations operations = mock(CustomerIdentityOperations.class);
    private final CustomerIdentitySaasClient central = mock(CustomerIdentitySaasClient.class);
    private final SyncOutboxService outbox = mock(SyncOutboxService.class);
    private final CustomerIdentityCoordinator coordinator = new CustomerIdentityCoordinator(operations, central, outbox);
    private final TransactionTemplate transactions = new TransactionTemplate(new TestTransactionManager());
    private final Company company = PartyTestData.company();
    private final UUID storeId = PartyTestData.store(company).getId();
    private final CustomerDocumentIdentity identity = CustomerDocumentIdentity.validate(DocumentType.DNI, "12345678Z");

    @Test
    void requiresActualBusinessTransactionBeforePreparingOrContactingSaas() {
        Customer candidate = customer(UUID.randomUUID(), "Cliente de prueba");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertThrows(IllegalStateException.class, () -> reserve(candidate));
        verifyNoInteractions(operations, central, outbox);
    }

    @Test
    void commitsExactlyTheApprovedProfileAndLinksTheReservedCentralIdentity() {
        Customer customer = customer(UUID.randomUUID(), "Álvarez 商店");
        var operation = prepared(customer, "PENDING");
        var reservation = new CustomerIdentitySaasClient.Reservation(operation.operationId(), UUID.randomUUID(), 1L, "DNI", "12345678Z");
        Map<String, Object> expectedProfile = expectedProfile(customer.getFiscalName());
        when(central.reserve(operation, expectedProfile)).thenReturn(reservation);

        var approval = transactions.execute(status -> {
            var approved = reserve(customer);
            assertEquals(customer.getId(), approved.customerId());
            assertEquals(expectedProfile, approved.profile());
            coordinator.complete(approved, customer);
            return approved;
        });

        assertSame(reservation, approval.reservation());
        assertEquals(reservation.customerId(), customer.getSaasCustomerId());
        assertEquals(1L, customer.getSaasIdentityRevision());
        ArgumentCaptor<SyncOutboundEventCommand> event = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        var ordered = inOrder(operations, central, outbox);
        ordered.verify(operations).prepare(company.getId(), storeId, null, null, null, identity);
        ordered.verify(operations).lock(operation.operationId());
        ordered.verify(central).reserve(operation, expectedProfile);
        ordered.verify(outbox).enqueue(event.capture());
        ordered.verify(operations).committed(operation.operationId());
        assertEquals(company.getId(), event.getValue().companyId());
        assertEquals(storeId, event.getValue().storeId());
        assertEquals(customer.getId(), event.getValue().entityId());
        assertEquals("CUSTOMER_IDENTITY", event.getValue().entityType());
        assertEquals(SyncOperation.ACTUALIZAR, event.getValue().operation());
        assertNull(event.getValue().terminalId());
        assertNull(event.getValue().storeSequence());
        Map<String, Object> expectedPayload = new LinkedHashMap<>(expectedProfile);
        expectedPayload.put("operationId", operation.operationId().toString());
        assertEquals(expectedPayload, event.getValue().payload());
        assertEquals(expectedProfile, approval.profile());
        verify(operations, never()).cancellationPending(any());
        verify(central, never()).cancel(any());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void forwardsExistingIdentityAndExpectedRevisionWhenPreparingAnUpdate() {
        Customer existing = customer(UUID.randomUUID(), "Cliente existente");
        UUID centralId = UUID.randomUUID();
        existing.linkSaasIdentity(centralId, 3L);
        var operation = operation(existing.getId(), "PENDING", false, centralId, 3L);
        when(operations.prepare(company.getId(), storeId, existing.getId(), centralId, 3L, identity)).thenReturn(operation);
        when(operations.lock(operation.operationId())).thenReturn(operation);
        when(central.reserve(eq(operation), any())).thenReturn(new CustomerIdentitySaasClient.Reservation(
                operation.operationId(), centralId, 4L, "DNI", "12345678Z"));

        transactions.executeWithoutResult(status -> {
            var approval = coordinator.reserve(company.getId(), storeId, existing, identity, existing);
            coordinator.complete(approval, existing);
        });

        assertEquals(centralId, existing.getSaasCustomerId());
        assertEquals(4L, existing.getSaasIdentityRevision());
        verify(operations).prepare(company.getId(), storeId, existing.getId(), centralId, 3L, identity);
        verify(operations).committed(operation.operationId());
        verify(operations, never()).cancellationPending(any());
    }

    @Test
    void rollbackAfterReservationQueuesCancellationOnlyAfterTransactionCompletion() {
        Customer customer = customer(UUID.randomUUID(), "Cliente de prueba");
        var operation = prepared(customer, "PENDING");
        when(central.reserve(eq(operation), any())).thenReturn(reservation(operation));

        transactions.executeWithoutResult(status -> {
            reserve(customer);
            status.setRollbackOnly();
            verify(operations, never()).cancellationPending(any());
        });

        verify(operations).cancellationPending(operation.operationId());
        verify(operations, never()).committed(any());
        verifyNoInteractions(outbox);
        verify(central, never()).cancel(any());
        assertNull(customer.getSaasCustomerId());
    }

    @Test
    void editingAnAdoptedCopyRetainsCentralCodeEvenWhenCandidateHasOnlyTheLocalStoreCode() {
        Customer existing = customer(UUID.randomUUID(), "Cliente adoptado");
        UUID centralId = UUID.randomUUID();
        existing.linkAdoptedSaasIdentity(centralId, 0L, "CENTRAL-MASTER-CODE");
        Customer candidate = customer(existing.getId(), "Perfil editado");
        var operation = operation(existing.getId(), "PENDING", false, centralId, 0L);
        when(operations.prepare(company.getId(), storeId, existing.getId(), centralId, 0L, identity)).thenReturn(operation);
        when(operations.lock(operation.operationId())).thenReturn(operation);
        when(central.reserve(eq(operation), any())).thenReturn(new CustomerIdentitySaasClient.Reservation(
                operation.operationId(), centralId, 1L, "DNI", "12345678Z"));
        transactions.executeWithoutResult(status -> {
            var approval = coordinator.reserve(company.getId(), storeId, existing, identity, candidate);
            assertEquals("CENTRAL-MASTER-CODE", approval.profile().get("clientId"));
            existing.update(candidate.getFiscalName(), candidate.getDocumentType(), candidate.getDocumentNumber(),
                    candidate.getFiscalAddress(), candidate.getPhone(), candidate.getEmail(), null, CustomerRate.VENTA, BigDecimal.ZERO);
            coordinator.complete(approval, existing);
        });
        assertEquals("C-001-000001", existing.getClientId());
        assertEquals("CENTRAL-MASTER-CODE", existing.getSaasClientCode());
        assertEquals(1L, existing.getSaasIdentityRevision());
    }

    @Test
    void uncertainTimeoutReturns503AndRetainsDurableCancellationForLateReservation() {
        Customer customer = customer(UUID.randomUUID(), "Cliente de prueba");
        var operation = prepared(customer, "PENDING");
        var failure = CustomerIdentityException.unavailable();
        when(central.reserve(eq(operation), any())).thenThrow(failure);

        var thrown = assertThrows(CustomerIdentityException.class,
                () -> transactions.executeWithoutResult(status -> reserve(customer)));

        assertSame(failure, thrown);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.status());
        assertEquals("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE", thrown.code());
        verify(operations).cancellationPending(operation.operationId());
        verify(operations, never()).committed(any());
        verifyNoInteractions(outbox);
        verify(central, never()).cancel(any());
    }

    @Test
    void failureWhileEnqueuingFinalizationRollsBackAndQueuesCancellation() {
        Customer customer = customer(UUID.randomUUID(), "Cliente de prueba");
        var operation = prepared(customer, "PENDING");
        when(central.reserve(eq(operation), any())).thenReturn(reservation(operation));
        when(outbox.enqueue(any())).thenThrow(new IllegalStateException("Synthetic outbox write failure"));

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status ->
                coordinator.complete(reserve(customer), customer)));

        verify(operations).cancellationPending(operation.operationId());
        verify(operations, never()).committed(any());
        verify(central, never()).cancel(any());
    }

    @Test
    void changedProfileBetweenReservationAndCompletionBlocksTheWriteAndCancels() {
        Customer customer = customer(UUID.randomUUID(), "Nombre aprobado");
        var operation = prepared(customer, "PENDING");
        when(central.reserve(eq(operation), any())).thenReturn(reservation(operation));

        var thrown = assertThrows(CustomerIdentityException.class, () -> transactions.executeWithoutResult(status -> {
            var approval = reserve(customer);
            customer.update("Nombre no aprobado", DocumentType.DNI, "12345678Z", customer.getFiscalAddress(),
                    customer.getPhone(), customer.getEmail(), null, CustomerRate.VENTA, BigDecimal.ZERO);
            assertEquals("Nombre aprobado", approval.profile().get("fiscalName"));
            coordinator.complete(approval, customer);
        }));

        assertEquals("CUSTOMER_IDENTITY_CONFLICT", thrown.code());
        assertNull(customer.getSaasCustomerId());
        verifyNoInteractions(outbox);
        verify(operations, never()).committed(any());
        verify(operations).cancellationPending(operation.operationId());
    }

    @Test
    void reservationCannotBeAppliedToAnotherLocalCustomerWithTheSameProfile() {
        Customer customer = customer(UUID.randomUUID(), "Cliente de prueba");
        Customer other = customer(UUID.randomUUID(), "Cliente de prueba");
        var operation = prepared(customer, "PENDING");
        when(central.reserve(eq(operation), any())).thenReturn(reservation(operation));

        assertThrows(CustomerIdentityException.class, () -> transactions.executeWithoutResult(status ->
                coordinator.complete(reserve(customer), other)));

        assertNull(other.getSaasCustomerId());
        verifyNoInteractions(outbox);
        verify(operations, never()).committed(any());
        verify(operations).cancellationPending(operation.operationId());
    }

    @ParameterizedTest
    @ValueSource(strings = { "CANCEL_PENDING", "CANCELLED", "LOCAL_COMMITTED" })
    void refusesAnIntentThatStoppedBeingPendingWhileWaitingForItsLock(String state) {
        Customer customer = customer(UUID.randomUUID(), "Cliente de prueba");
        prepared(customer, state);

        var thrown = assertThrows(CustomerIdentityException.class,
                () -> transactions.executeWithoutResult(status -> reserve(customer)));

        assertEquals("CUSTOMER_IDENTITY_CONFLICT", thrown.code());
        verifyNoInteractions(central, outbox);
        verify(operations, never()).cancellationPending(any());
        verify(operations, never()).committed(any());
    }

    @Test
    void rejectsOverlengthProfileBeforePreparingADurableIntent() {
        Customer customer = customer(UUID.randomUUID(), "A".repeat(256));
        assertThrows(IllegalArgumentException.class,
                () -> transactions.executeWithoutResult(status -> reserve(customer)));
        verifyNoInteractions(operations, central, outbox);
    }

    @Test
    void requiresAnAssignedClientCodeBeforePreparingADurableIntent() {
        Customer customer = new Customer(company, "Cliente sin código", DocumentType.DNI, "12345678Z",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        assertThrows(IllegalStateException.class,
                () -> transactions.executeWithoutResult(status -> reserve(customer)));
        verifyNoInteractions(operations, central, outbox);
    }

    @Test
    void retriesRecoverAbandonedThenLeaveFailedCancellationsPendingAndContinueWithOthers() {
        var failed = operation(UUID.randomUUID(), "CANCEL_PENDING", true, null, null);
        var successful = operation(UUID.randomUUID(), "CANCEL_PENDING", true, null, null);
        when(operations.cancellations()).thenReturn(List.of(failed, successful));
        doThrow(CustomerIdentityException.conflict()).when(central).cancel(failed);

        coordinator.retryCancellations();

        var ordered = inOrder(operations, central);
        ordered.verify(operations).recoverAbandoned();
        ordered.verify(operations).cancellations();
        ordered.verify(central).cancel(failed);
        ordered.verify(central).cancel(successful);
        ordered.verify(operations).cancelled(successful.operationId());
        verify(operations, never()).cancelled(failed.operationId());
        verify(operations, never()).committed(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void unavailableSaasStopsTheBatchWithoutWaitingForEveryPendingTimeout() {
        var first = operation(UUID.randomUUID(), "CANCEL_PENDING", true, null, null);
        var second = operation(UUID.randomUUID(), "CANCEL_PENDING", true, null, null);
        when(operations.cancellations()).thenReturn(List.of(first, second));
        doThrow(CustomerIdentityException.unavailable()).when(central).cancel(first);

        coordinator.retryCancellations();

        verify(operations).recoverAbandoned();
        verify(central).cancel(first);
        verify(central, never()).cancel(second);
        verify(operations, never()).cancelled(any());
    }

    @Test
    void retryWithNoCancellationsStillPerformsCrashRecovery() {
        when(operations.cancellations()).thenReturn(List.of());
        coordinator.retryCancellations();
        verify(operations).recoverAbandoned();
        verify(operations).cancellations();
        verifyNoInteractions(central, outbox);
    }

    private CustomerIdentityCoordinator.Approval reserve(Customer candidate) {
        return coordinator.reserve(company.getId(), storeId, null, identity, candidate);
    }

    private CustomerIdentityOperations.Operation prepared(Customer customer, String lockedState) {
        var pending = operation(customer.getId(), "PENDING", true, null, null);
        var locked = new CustomerIdentityOperations.Operation(pending.operationId(), pending.companyId(), pending.storeId(),
                pending.customerId(), pending.creating(), pending.documentType(), pending.documentNumber(),
                pending.expectedCustomerId(), pending.expectedRevision(), lockedState);
        when(operations.prepare(eq(company.getId()), eq(storeId), isNull(), isNull(), isNull(), eq(identity))).thenReturn(pending);
        when(operations.lock(pending.operationId())).thenReturn(locked);
        return locked;
    }

    private CustomerIdentityOperations.Operation operation(UUID customerId, String state, boolean creating,
            UUID expectedId, Long revision) {
        return new CustomerIdentityOperations.Operation(UUID.randomUUID(), company.getId(), storeId, customerId,
                creating, DocumentType.DNI, "12345678Z", expectedId, revision, state);
    }

    private static CustomerIdentitySaasClient.Reservation reservation(CustomerIdentityOperations.Operation operation) {
        return new CustomerIdentitySaasClient.Reservation(operation.operationId(), UUID.randomUUID(), 1L, "DNI", "12345678Z");
    }

    private Customer customer(UUID id, String name) {
        var customer = new Customer(id, company, name, DocumentType.DNI, "12345678Z",
                new FiscalAddress("Calle de prueba 1", "35001", "Las Palmas", "Las Palmas", "ES"),
                "600000001", "test@example.invalid", "Nota local no enviada", CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(storeId, "C-001-000001");
        return customer;
    }

    private static Map<String, Object> expectedProfile(String name) {
        return Map.of("clientId", "C-001-000001", "fiscalName", name,
                "phone", "600000001", "email", "test@example.invalid",
                "address", Map.of("address", "Calle de prueba 1", "postalCode", "35001",
                        "city", "Las Palmas", "province", "Las Palmas", "country", "ES"));
    }

    /** Exercises Spring completion callbacks; durable DB rollback is covered by PostgreSQL tests. */
    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
