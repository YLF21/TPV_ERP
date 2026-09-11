package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboxStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CustomerAdoptionServiceTest {
    final CustomerRepository customers = mock(CustomerRepository.class);
    final CustomerService views = mock(CustomerService.class);
    final PartyContext context = mock(PartyContext.class);
    final PartyCodeAllocator codes = mock(PartyCodeAllocator.class);
    final CustomerAdoptionOperations operations = mock(CustomerAdoptionOperations.class);
    final CustomerIdentitySaasClient central = mock(CustomerIdentitySaasClient.class);
    final SyncOutboxService outbox = mock(SyncOutboxService.class);
    final Transactions manager = new Transactions();
    final CustomerAdoptionService service = new CustomerAdoptionService(customers, views, context, codes, operations, central, outbox, manager);
    final TransactionTemplate transaction = new TransactionTemplate(manager);
    final Company company = PartyTestData.company();
    final Store store = PartyTestData.store(company);
    final UUID centralId = UUID.randomUUID();
    final UUID localId = UUID.randomUUID();
    final CustomerAdoptionApi.Adopt request = new CustomerAdoptionApi.Adopt(centralId, 0L, DocumentType.DNI, "12345678Z");
    final CustomerAdoptionOperations.Operation operation = new CustomerAdoptionOperations.Operation(UUID.randomUUID(),
            company.getId(), store.getId(), localId, centralId, 0, DocumentType.DNI, "12345678Z", "PENDING");

    @BeforeEach void setup() {
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(codes.nextClient(store)).thenReturn("C-002-000001");
    }

    @Test void anAmbientTransactionIsRejectedBeforeAnyIndependentWrite() {
        assertThatThrownBy(() -> transaction.execute(status -> service.adopt(request))).isInstanceOf(IllegalStateException.class);
        transaction.setReadOnly(true);
        assertThatThrownBy(() -> transaction.execute(status -> service.adopt(request))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(operations, central, customers, codes, outbox);
    }

    @ParameterizedTest @ValueSource(strings = {"0.5", "0.0", "9223372036854775808", "\"0\""})
    void selectedRevisionCannotBeCoercedFromFractionOrOverflow(String revision) {
        String json = "{\"customerId\":\"" + centralId + "\",\"expectedRevision\":" + revision
                + ",\"documentType\":\"DNI\",\"documentNumber\":\"12345678Z\"}";
        assertThatThrownBy(() -> tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, CustomerAdoptionApi.Adopt.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @Test void copiesOnlySelectedFiscalProfileWithCurrentLocalDefaultsAndSeparateCodeAtRevisionZero() {
        prepare();
        var result = service.adopt(request);
        assertThat(result.centralLinkStatus()).isEqualTo("PENDING_SYNC");
        var copy = ArgumentCaptor.forClass(Customer.class);
        verify(customers).saveAndFlush(copy.capture());
        var customer = copy.getValue();
        assertThat(customer.getId()).isEqualTo(localId);
        assertThat(customer.getSaasCustomerId()).isEqualTo(centralId);
        assertThat(customer.getSaasIdentityRevision()).isZero();
        assertThat(customer.getClientId()).isEqualTo("C-002-000001");
        assertThat(customer.getSaasClientCode()).isEqualTo("CENTRAL-001");
        assertThat(customer.getFiscalName()).isEqualTo("Cliente central");
        assertThat(customer.getFiscalAddress().getCountry()).isEqualTo("ES");
        assertThat(customer.getRate()).isEqualTo(CustomerRate.VENTA);
        assertThat(customer.getDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(customer.isCreditEnabled()).isTrue();
        assertThat(customer.getCreditLimit()).isNull();
        assertThat(customer.getPaymentTermDays()).isEqualTo(30);
        assertThat(customer.hasCommercialConsent()).isFalse();
        assertThat(customer.getBirthday()).isNull();
        assertThat(customer.getNotes()).isNull();
        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(command.capture());
        assertThat(command.getValue().entityType()).isEqualTo("CUSTOMER_ADOPTION");
        assertThat(command.getValue().entityId()).isEqualTo(localId);
        assertThat(command.getValue().payload()).isEqualTo(Map.of("operationId", operation.operationId().toString()));
        verify(operations).committed(operation.operationId());
        verify(operations, never()).cancellationPending(any());
    }

    @Test void timeoutCannotPersistCustomerOrOutboxAndQueuesDurableCancellationAfterRollback() {
        prepare();
        when(central.reserveAdoption(operation)).thenThrow(CustomerIdentityException.unavailable());
        assertThatThrownBy(() -> service.adopt(request))
                .hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE");
        verify(customers, never()).saveAndFlush(any());
        verify(outbox, never()).enqueue(any());
        verify(operations).cancellationPending(operation.operationId());
        verify(operations, never()).committed(any());
    }

    @Test void outboxFailureRollsBackAndCancelsWithoutClaimingSuccess() {
        prepare();
        when(outbox.enqueue(any())).thenThrow(new IllegalStateException("Synthetic failure"));
        assertThatThrownBy(() -> service.adopt(request)).isInstanceOf(IllegalStateException.class);
        verify(operations).cancellationPending(operation.operationId());
        verify(operations, never()).committed(any());
    }

    @Test void sameNifDoesNotAutolinkLegacyLocalCustomer() {
        var legacy = customer();
        when(customers.findByCompanyAndNormalizedDocument(company.getId(), "12345678Z")).thenReturn(Optional.of(legacy));
        assertThatThrownBy(() -> service.adopt(request)).hasMessage("CUSTOMER_DOCUMENT_DUPLICATE");
        assertThat(legacy.getSaasCustomerId()).isNull();
        verifyNoInteractions(operations, central, outbox);
    }

    @ParameterizedTest @ValueSource(strings = {"PENDIENTE", "ENVIADO", "ERROR"})
    void repeatedAdoptionReturnsExistingCopyWithoutNewHttpOrLocalCode(String status) {
        var existing = customer();
        existing.linkAdoptedSaasIdentity(centralId, 0, "CENTRAL-001");
        when(customers.findByCompanyIdAndSaasCustomerId(company.getId(), centralId)).thenReturn(Optional.of(existing));
        var event = mock(SyncOutboxEvent.class);
        when(event.getStatus()).thenReturn(SyncOutboxStatus.valueOf(status));
        when(outbox.latest(company.getId(), store.getId(), "CUSTOMER_ADOPTION", localId)).thenReturn(Optional.of(event));
        var result = service.adopt(request);
        assertThat(result.centralLinkStatus()).isEqualTo(status.equals("ENVIADO") ? "LINKED" : "PENDING_SYNC");
        verifyNoInteractions(central, operations, codes);
        verify(customers, never()).saveAndFlush(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test void doubleClickRechecksExistingCopyAfterTheCodeCounterLock() {
        prepare();
        var existing = customer(); existing.linkAdoptedSaasIdentity(centralId, 0, "CENTRAL-001");
        when(customers.findByCompanyIdAndSaasCustomerId(company.getId(), centralId))
                .thenReturn(Optional.empty(), Optional.of(existing));
        var event = mock(SyncOutboxEvent.class);
        when(event.getStatus()).thenReturn(SyncOutboxStatus.PENDIENTE);
        when(outbox.latest(company.getId(), store.getId(), "CUSTOMER_ADOPTION", localId)).thenReturn(Optional.of(event));
        service.adopt(request);
        verifyNoInteractions(central);
        verify(operations).cancellationPending(operation.operationId());
        verify(customers, never()).saveAndFlush(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test void aContenderWhoseSharedIntentAlreadyCommittedReturnsThatCopyWithoutCounterOrHttp() {
        prepare();
        var existing = customer(); existing.linkAdoptedSaasIdentity(centralId, 0, "CENTRAL-001");
        var committed = new CustomerAdoptionOperations.Operation(operation.operationId(), company.getId(), store.getId(),
                localId, centralId, 0, DocumentType.DNI, "12345678Z", "LOCAL_COMMITTED");
        doReturn(committed).when(operations).lock(operation.operationId());
        when(customers.findByIdAndCompanyId(localId, company.getId())).thenReturn(Optional.of(existing));
        var event = mock(SyncOutboxEvent.class);
        when(event.getStatus()).thenReturn(SyncOutboxStatus.PENDIENTE);
        when(outbox.latest(company.getId(), store.getId(), "CUSTOMER_ADOPTION", localId)).thenReturn(Optional.of(event));
        assertThat(service.adopt(request).centralLinkStatus()).isEqualTo("PENDING_SYNC");
        verifyNoInteractions(codes, central);
        verify(operations, never()).cancellationPending(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test void existingVerifiedCopyCanLinkAnotherStoreWithoutOverwritingItsLocalProfile() {
        prepare();
        var existing = customer(); existing.linkAdoptedSaasIdentity(centralId, 0, "CENTRAL-001");
        when(customers.findByCompanyIdAndSaasCustomerId(company.getId(), centralId)).thenReturn(Optional.of(existing));
        when(operations.prepare(eq(company.getId()), eq(store.getId()), eq(localId), eq(centralId), eq(0L), any())).thenReturn(operation);
        service.adopt(request);
        assertThat(existing.getFiscalName()).isEqualTo("Ficha local");
        assertThat(existing.getSaasIdentityRevision()).isZero();
        verify(customers, never()).saveAndFlush(any());
        verify(outbox).enqueue(any());
    }

    @Test void lookupReturnsLocalCopyDuringPendingSyncWithoutGuessingByNif() {
        when(central.lookup(eq(company.getId()), eq(store.getId()), any())).thenReturn(profile());
        var existing = customer(); existing.linkAdoptedSaasIdentity(centralId, 0, "CENTRAL-001");
        when(customers.findByCompanyIdAndSaasCustomerId(company.getId(), centralId)).thenReturn(Optional.of(existing));
        assertThat(service.lookup(new CustomerAdoptionApi.Lookup(DocumentType.DNI, "12-345678z")).localCustomerId()).isEqualTo(localId);
        verify(customers, never()).findByCompanyAndNormalizedDocument(any(), any());
    }

    @Test void inconsistentCentralLocalIdFailsClosedInsteadOfPretendingLocalCopyExists() {
        when(central.lookup(eq(company.getId()), eq(store.getId()), any())).thenReturn(profile().withLocalCustomerId(UUID.randomUUID()));
        assertThatThrownBy(() -> service.lookup(new CustomerAdoptionApi.Lookup(DocumentType.DNI, "12345678Z")))
                .hasMessage("CUSTOMER_IDENTITY_CONFLICT");
    }

    @Test void cancellationWorkerStopsOnUnavailableSaasAndRetainsIntent() {
        when(operations.cancellations()).thenReturn(List.of(operation, operation));
        doThrow(CustomerIdentityException.unavailable()).when(central).cancelAdoption(operation);
        service.retryCancellations();
        verify(operations).recoverAbandoned();
        verify(central, times(1)).cancelAdoption(operation);
        verify(operations, never()).cancelled(any());
    }

    private void prepare() {
        when(operations.prepare(eq(company.getId()), eq(store.getId()), isNull(), eq(centralId), eq(0L), any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return operation;
        });
        when(operations.lock(operation.operationId())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return operation;
        });
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(operations).cancellationPending(any());
        when(central.reserveAdoption(operation)).thenReturn(new CustomerAdoptionApi.Reservation(operation.operationId(), localId, profile()));
    }
    private CustomerAdoptionApi.Profile profile() {
        return new CustomerAdoptionApi.Profile(centralId, 0L, "CENTRAL-001", "Cliente central", DocumentType.DNI,
                "12345678Z", new CustomerAdoptionApi.Address("Calle 1", "35001", "Las Palmas", "Las Palmas", "ES"),
                "600000001", "test@example.invalid", true, null);
    }
    private Customer customer() {
        var customer = new Customer(localId, company, "Ficha local", DocumentType.DNI, "12345678Z",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-002-000001");
        return customer;
    }
    static final class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
