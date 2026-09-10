package com.tpverp.backend.party;

import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CustomerIdentityCoordinator {
    private final CustomerIdentityOperations operations;
    private final CustomerIdentitySaasClient central;
    private final SyncOutboxService outbox;

    public CustomerIdentityCoordinator(CustomerIdentityOperations operations, CustomerIdentitySaasClient central,
            SyncOutboxService outbox) {
        this.operations = operations;
        this.central = central;
        this.outbox = outbox;
    }

    public Approval reserve(UUID companyId, UUID storeId, Customer existing, CustomerDocumentIdentity identity,
            Customer candidate) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Customer identity registration requires a transaction");
        }
        var profile = profile(candidate);
        var operation = operations.prepare(companyId, storeId, existing == null ? null : existing.getId(),
                existing == null ? null : existing.getSaasCustomerId(),
                existing == null ? null : existing.getSaasIdentityRevision(), identity);
        operation = operations.lock(operation.operationId());
        if (!"PENDING".equals(operation.state())) throw CustomerIdentityException.conflict();
        var owned = operation;
        // Even an HTTP timeout may have reserved the number. A cancellation tombstone makes
        // late-arriving reserve requests harmless; the old identity remains owned until commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) operations.cancellationPending(owned.operationId());
            }
        });
        return new Approval(operation, central.reserve(operation, profile), profile);
    }

    public void complete(Approval approval, Customer customer) {
        var operation = approval.operation();
        if (!customer.getId().equals(operation.customerId()) || !profile(customer).equals(approval.profile())) {
            throw CustomerIdentityException.conflict();
        }
        customer.linkSaasIdentity(approval.reservation().customerId(), approval.reservation().revision());
        var payload = new LinkedHashMap<>(approval.profile());
        payload.put("operationId", operation.operationId().toString());
        outbox.enqueue(new SyncOutboundEventCommand(operation.companyId(), operation.storeId(), null,
                "CUSTOMER_IDENTITY", customer.getId(), SyncOperation.ACTUALIZAR, payload));
        operations.committed(operation.operationId());
    }

    static Map<String, Object> profile(Customer customer) {
        requireLength(customer.getFiscalName(), 255, "nombreFiscal");
        requireLength(customer.getPhone(), 64, "telefono");
        requireLength(customer.getEmail(), 320, "email");
        if (customer.getClientId() == null) throw new IllegalStateException("Customer code is required before reservation");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("clientId", customer.getClientId());
        payload.put("fiscalName", customer.getFiscalName());
        payload.put("phone", customer.getPhone());
        payload.put("email", customer.getEmail());
        FiscalAddress address = customer.getFiscalAddress();
        var addressData = new LinkedHashMap<String, Object>();
        if (address != null) {
            addressData.put("address", address.getAddress());
            addressData.put("postalCode", address.getPostalCode());
            addressData.put("city", address.getCity());
            addressData.put("province", address.getProvince());
            addressData.put("country", address.getCountry());
        }
        payload.put("address", addressData);
        return java.util.Collections.unmodifiableMap(payload);
    }

    private static void requireLength(String value, int maximum, String field) {
        if (value != null && value.length() > maximum) throw new IllegalArgumentException(field + ": " + maximum);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void retryCancellations() {
        operations.recoverAbandoned();
        for (var operation : operations.cancellations()) {
            try {
                central.cancel(operation);
                operations.cancelled(operation.operationId());
            } catch (CustomerIdentityException exception) {
                // Durable CANCEL_PENDING stays reserved until the owner can confirm cancellation.
                // No identity, upstream response or credential is written to logs.
                // One unavailable server must not occupy the shared scheduler for 50 timeouts.
                if (exception.status() == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) break;
            }
        }
    }

    public record Approval(CustomerIdentityOperations.Operation operation,
            CustomerIdentitySaasClient.Reservation reservation, Map<String, Object> profile) {
        public UUID customerId() { return operation.customerId(); }
    }
}
