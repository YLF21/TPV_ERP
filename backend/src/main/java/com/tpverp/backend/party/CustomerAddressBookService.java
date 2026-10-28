package com.tpverp.backend.party;

import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The local copy is usable offline. A pending book is never overwritten by a remote refresh. */
@Service
public class CustomerAddressBookService {
    private final CustomerRepository customers;
    private final PartyContext context;
    private final CustomerIdentitySaasClient central;
    private final SyncOutboxService outbox;
    private final TransactionTemplate transactions;

    public CustomerAddressBookService(CustomerRepository customers, PartyContext context,
            CustomerIdentitySaasClient central, SyncOutboxService outbox, PlatformTransactionManager manager) {
        this.customers = customers; this.context = context; this.central = central; this.outbox = outbox;
        transactions = new TransactionTemplate(manager);
    }

    static List<CustomerAddressEntry> prepareUpdate(Customer customer, List<CustomerAddressEntry> addresses,
            Long expectedVersion, FiscalAddress address, String phone, String email, String notes) {
        List<CustomerAddressEntry> previous = customer.getAddresses();
        List<CustomerAddressEntry> requested;
        if (addresses != null) {
            requested = CustomerAddressEntry.validated(addresses);
        } else {
            var legacy = new ArrayList<>(previous);
            legacy.set(0, new CustomerAddressEntry(previous.getFirst().id(), CustomerAddressEntry.Address.from(address),
                    PartyValues.optional(phone), PartyValues.optional(email), PartyValues.optional(notes)));
            requested = CustomerAddressEntry.validated(legacy);
        }
        if (requested.equals(previous)) return previous;
        if (addresses != null && (expectedVersion == null || expectedVersion != customer.getVersion())) {
            throw CustomerAddressEntry.failure("VERSION_CONFLICT");
        }
        if ("PENDING".equals(customer.getAddressSyncStatus())) throw CustomerAddressEntry.failure("PENDING");
        if ("CONFLICT".equals(customer.getAddressSyncStatus())) throw CustomerAddressEntry.failure("CONFLICT");
        if (customer.getSaasCustomerId() != null && customer.getAddressBookRevision() == null) {
            throw CustomerAddressEntry.failure("REFRESH_REQUIRED");
        }
        return requested;
    }

    /** Called within the customer transaction; its rollback also removes the outbox event. */
    void enqueue(Customer customer) {
        if (customer.getSaasCustomerId() == null) return;
        UUID operationId = UUID.randomUUID();
        outbox.enqueue(new SyncOutboundEventCommand(customer.getCompany().getId(), context.currentStore().getId(),
                null, "CUSTOMER_ADDRESSES", customer.getId(), SyncOperation.ACTUALIZAR,
                Map.of("operationId", operationId.toString(), "customerId", customer.getSaasCustomerId().toString(),
                        "expectedRevision", customer.getAddressBookRevision(), "addresses", customer.getAddresses())));
        customer.addressPending(operationId);
    }

    public void refresh(UUID id, boolean useCentral, Long expectedVersion) {
        var selected = transactions.execute(status -> {
            UUID companyId = context.currentCompany().getId();
            var customer = customers.findByIdAndCompanyId(id, companyId)
                    .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
            return new Selection(companyId, context.currentStore().getId(), customer.getSaasCustomerId(),
                    customer.getAddressOperationId());
        });
        if (selected.centralId() == null) return;
        Snapshot snapshot;
        try {
            snapshot = central.addressSnapshot(selected.companyId(), selected.storeId(), id,
                    selected.centralId(), selected.operationId());
        } catch (CustomerIdentityException failure) {
            if (!useCentral && (failure.status() == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                    || failure.status() == org.springframework.http.HttpStatus.NOT_FOUND)) return;
            throw failure;
        }
        transactions.executeWithoutResult(status -> {
            var customer = customers.findLockedByIdAndCompanyId(id, selected.companyId())
                    .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
            if (!Objects.equals(customer.getSaasCustomerId(), selected.centralId())
                    || !Objects.equals(customer.getAddressOperationId(), selected.operationId())) {
                if (useCentral) throw CustomerAddressEntry.failure("VERSION_CONFLICT");
                return;
            }
            if (useCentral && (expectedVersion == null || expectedVersion != customer.getVersion())) {
                throw CustomerAddressEntry.failure("VERSION_CONFLICT");
            }
            applySnapshot(customer, snapshot, useCentral);
        });
    }

    static void applySnapshot(Customer customer, Snapshot snapshot, boolean useCentral) {
        if (customer.getAddressBookRevision() != null && snapshot.revision() < customer.getAddressBookRevision()) {
            throw CustomerAddressEntry.failure("VERSION_CONFLICT");
        }
        if (customer.getAddressOperationId() != null) {
            if (!"APPLIED".equals(snapshot.operationStatus()) && !"CONFLICT".equals(snapshot.operationStatus())) {
                if (useCentral) throw CustomerAddressEntry.failure("PENDING");
                return; // A delayed event can still apply; never abandon it without a terminal receipt.
            }
            if ("CONFLICT".equals(snapshot.operationStatus()) && !useCentral) {
                customer.addressConflict(); return;
            }
        } else if (!useCentral && customer.getAddressBookRevision() == null
                && !sameContacts(customer.getAddresses(), snapshot.addresses())) {
            customer.addressConflict(); return; // Preserve differing legacy details for explicit reconciliation.
        } else if (!useCentral && "CONFLICT".equals(customer.getAddressSyncStatus())) {
            return;
        }
        customer.addressConfirmed(snapshot.addresses(), snapshot.revision());
    }

    private static boolean sameContacts(List<CustomerAddressEntry> local, List<CustomerAddressEntry> remote) {
        if (local.size() != remote.size()) return false;
        for (int i = 0; i < local.size(); i++) if (!local.get(i).sameContact(remote.get(i))) return false;
        return true;
    }

    private record Selection(UUID companyId, UUID storeId, UUID centralId, UUID operationId) { }
    public record Snapshot(UUID customerId, Long revision, List<CustomerAddressEntry> addresses,
            String operationStatus, UUID operationId) { }
}
