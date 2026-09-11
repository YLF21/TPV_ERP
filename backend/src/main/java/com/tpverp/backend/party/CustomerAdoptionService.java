package com.tpverp.backend.party;

import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboxStatus;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerAdoptionService {
    private final CustomerRepository customers;
    private final CustomerService views;
    private final PartyContext context;
    private final PartyCodeAllocator codes;
    private final CustomerAdoptionOperations operations;
    private final CustomerIdentitySaasClient central;
    private final SyncOutboxService outbox;
    private final TransactionTemplate business;
    private final TransactionTemplate reads;

    public CustomerAdoptionService(CustomerRepository customers, CustomerService views, PartyContext context,
            PartyCodeAllocator codes, CustomerAdoptionOperations operations, CustomerIdentitySaasClient central,
            SyncOutboxService outbox, PlatformTransactionManager transactions) {
        this.customers = customers; this.views = views; this.context = context; this.codes = codes;
        this.operations = operations; this.central = central; this.outbox = outbox;
        this.business = new TransactionTemplate(transactions);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    @Transactional(readOnly = true)
    public CustomerAdoptionApi.Profile lookup(CustomerAdoptionApi.Lookup request) {
        if (request == null) throw CustomerIdentityException.invalid();
        var identity = identity(request.documentType(), request.documentNumber());
        var company = context.currentCompany();
        var store = context.currentStore();
        var profile = central.lookup(company.getId(), store.getId(), identity);
        CustomerIdentitySaasClient.validateAdoptionProfile(profile);
        var existing = customers.findByCompanyIdAndSaasCustomerId(company.getId(), profile.customerId());
        if (profile.localCustomerId() != null && (existing.isEmpty()
                || !profile.localCustomerId().equals(existing.get().getId()))) throw CustomerIdentityException.conflict();
        return existing.map(customer -> profile.withLocalCustomerId(customer.getId())).orElse(profile);
    }

    /** Owns its transaction boundary: no connection may be retained while committing the durable intent. */
    public CustomerAdoptionApi.Adopted adopt(CustomerAdoptionApi.Adopt request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Customer adoption cannot join an existing transaction");
        }
        if (request == null || request.customerId() == null || request.expectedRevision() == null
                || request.expectedRevision() < 0) throw CustomerIdentityException.invalid();
        var identity = identity(request.documentType(), request.documentNumber());
        // Even with OpenEntityManagerInView, end this read transaction before durable preparation.
        var selection = reads.execute(status -> select(request, identity));
        if (selection.reused() != null) return selection.reused();
        var company = selection.company();
        var store = selection.store();
        var existing = selection.existing();
        // This independent commit is completed before obtaining a business connection or any counter lock.
        var prepared = operations.prepare(company.getId(), store.getId(), existing == null ? null : existing.getId(),
                request.customerId(), request.expectedRevision(), identity);
        try {
            var outcome = business.execute(status -> apply(prepared, company, store, request, identity));
            if (!outcome.intentCommitted()) operations.cancellationPending(prepared.operationId());
            return outcome.response();
        } catch (RuntimeException | Error failure) {
            // execute has already completed rollback and released its connection. Never open REQUIRES_NEW
            // from afterCompletion while contenders retain all remaining pool connections.
            try { operations.cancellationPending(prepared.operationId()); }
            catch (RuntimeException cancellationFailure) { failure.addSuppressed(cancellationFailure); }
            throw failure;
        }
    }

    private Selection select(CustomerAdoptionApi.Adopt request, CustomerDocumentIdentity identity) {
        var company = context.currentCompany();
        var store = context.currentStore();
        var existing = customers.findByCompanyIdAndSaasCustomerId(company.getId(), request.customerId());
        if (existing.isPresent()) {
            var previous = linkStatus(company.getId(), store.getId(), existing.get().getId());
            if (previous.isPresent()) return new Selection(company, store, existing.get(), result(existing.get(), previous.get()));
        }
        var sameNumber = customers.findByCompanyAndNormalizedDocument(company.getId(), identity.canonicalNumber());
        if (sameNumber.isPresent() && (existing.isEmpty() || !sameNumber.get().getId().equals(existing.get().getId()))) {
            // An unlinked legacy customer is not proof of central ownership, even with the same NIF.
            throw CustomerIdentityException.duplicate();
        }
        return new Selection(company, store, existing.orElse(null), null);
    }

    private Outcome apply(CustomerAdoptionOperations.Operation prepared, Company company, Store store,
            CustomerAdoptionApi.Adopt request, CustomerDocumentIdentity identity) {
        var operation = operations.lock(prepared.operationId());
        if (operation.state().equals("LOCAL_COMMITTED")) {
            var customer = customers.findByIdAndCompanyId(operation.customerId(), company.getId())
                    .orElseThrow(CustomerIdentityException::conflict);
            if (!request.customerId().equals(customer.getSaasCustomerId())) throw CustomerIdentityException.conflict();
            var status = linkStatus(company.getId(), store.getId(), customer.getId()).orElseThrow(CustomerIdentityException::conflict);
            return new Outcome(result(customer, status), true);
        }
        if (!operation.state().equals("PENDING")) throw CustomerIdentityException.conflict();
        String code = codes.nextClient(store);
        var existing = customers.findByCompanyIdAndSaasCustomerId(company.getId(), request.customerId());
        if (existing.isPresent()) {
            var previous = linkStatus(company.getId(), store.getId(), existing.get().getId());
            if (previous.isPresent()) return new Outcome(result(existing.get(), previous.get()), false);
            if (!operation.customerId().equals(existing.get().getId())) throw CustomerIdentityException.conflict();
        }
        var sameNumber = customers.findByCompanyAndNormalizedDocument(company.getId(), identity.canonicalNumber());
        if (sameNumber.isPresent() && (existing.isEmpty() || !sameNumber.get().getId().equals(existing.get().getId()))) {
            throw CustomerIdentityException.duplicate();
        }
        var reservation = central.reserveAdoption(operation);
        var profile = reservation.customer();
        CustomerIdentitySaasClient.validateAdoptionProfile(profile);
        if (!profile.active() || !operation.operationId().equals(reservation.operationId())
                || !operation.customerId().equals(reservation.localCustomerId())
                || !request.customerId().equals(profile.customerId()) || request.expectedRevision().longValue() != profile.revision()
                || identity.canonicalType() != profile.documentType() || !identity.canonicalNumber().equals(profile.documentNumber())
                || (profile.localCustomerId() != null && !operation.customerId().equals(profile.localCustomerId()))) {
            throw CustomerIdentityException.conflict();
        }
        Customer customer;
        if (existing.isPresent()) {
            customer = existing.get(); // No profile, balance, consent or historical identity is overwritten.
        } else {
            customer = new Customer(operation.customerId(), company, profile.fiscalName(), profile.documentType(),
                    profile.documentNumber(), profile.address() == null ? null : profile.address().local(),
                    profile.phone(), profile.email(), null, CustomerRate.VENTA, BigDecimal.ZERO);
            customer.assignClientCode(store.getId(), code);
            customer.linkAdoptedSaasIdentity(profile.customerId(), profile.revision(), profile.centralCode());
            customers.saveAndFlush(customer);
        }
        outbox.enqueue(new SyncOutboundEventCommand(company.getId(), store.getId(), null, "CUSTOMER_ADOPTION",
                customer.getId(), SyncOperation.ACTUALIZAR, Map.of("operationId", operation.operationId().toString())));
        operations.committed(operation.operationId());
        return new Outcome(result(customer, "PENDING_SYNC"), true);
    }

    private CustomerAdoptionApi.Adopted result(Customer customer, String status) {
        return new CustomerAdoptionApi.Adopted(views.get(customer.getId()), status);
    }

    private record Outcome(CustomerAdoptionApi.Adopted response, boolean intentCommitted) { }
    private record Selection(Company company, Store store, Customer existing, CustomerAdoptionApi.Adopted reused) { }

    private Optional<String> linkStatus(UUID companyId, UUID storeId, UUID customerId) {
        var event = outbox.latest(companyId, storeId, "CUSTOMER_ADOPTION", customerId)
                .or(() -> outbox.latest(companyId, storeId, "CUSTOMER_IDENTITY", customerId));
        return event.map(value -> value.getStatus() == SyncOutboxStatus.ENVIADO ? "LINKED" : "PENDING_SYNC");
    }

    private static CustomerDocumentIdentity identity(DocumentType type, String number) {
        try { return CustomerDocumentIdentity.validate(type, number); }
        catch (IllegalArgumentException exception) { throw CustomerIdentityException.invalid(); }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void retryCancellations() {
        operations.recoverAbandoned();
        for (var operation : operations.cancellations()) {
            try {
                central.cancelAdoption(operation);
                operations.cancelled(operation.operationId());
            } catch (CustomerIdentityException exception) {
                if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) break;
            }
        }
    }
}
