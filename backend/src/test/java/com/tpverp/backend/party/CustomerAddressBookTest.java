package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerAddressBookTest {
    private Customer customer() {
        var c = new Customer(PartyTestData.company(), "Customer", DocumentType.DNI, "12345678Z", null,
                "111", "one@example.test", "one", CustomerRate.VENTA, BigDecimal.ZERO);
        c.replaceAddresses(List.of(entry("First", "111"), entry("Second", "222")));
        return c;
    }
    private CustomerAddressEntry entry(String street, String phone) {
        return new CustomerAddressEntry(UUID.randomUUID(), new CustomerAddressEntry.Address(street, "35001",
                "Las Palmas", "Las Palmas", "ES"), phone, "one@example.test", "one");
    }
    @Test void legacyUpdatePreservesSecondaryAndDefaultId() {
        var c = customer(); var before = c.getAddresses();
        var result = CustomerAddressBookService.prepareUpdate(c, null, null,
                new FiscalAddress("Changed", "35001", "Las Palmas", "Las Palmas", "ES"), "333", c.getEmail(), c.getNotes());
        assertThat(result.getFirst().id()).isEqualTo(before.getFirst().id());
        assertThat(result.get(1)).isEqualTo(before.get(1));
        c.replaceAddresses(result);
        assertThat(c.getPhone()).isEqualTo("333");
        assertThat(c.getFiscalAddress().getAddress()).isEqualTo("Changed");
        assertThat(c.isCreditEnabled()).isTrue();
    }
    @Test void explicitCollectionRequiresVersionAndDefaultChangeUpdatesProjection() {
        var c = customer(); var reversed = List.of(c.getAddresses().get(1), c.getAddresses().getFirst());
        assertThatThrownBy(() -> CustomerAddressBookService.prepareUpdate(c, reversed, null, null, null, null, null))
                .isInstanceOf(CustomerIdentityException.class).hasMessage("CUSTOMER_ADDRESSES_VERSION_CONFLICT");
        var result = CustomerAddressBookService.prepareUpdate(c, reversed, c.getVersion(), null, null, null, null);
        c.replaceAddresses(result);
        assertThat(c.getPhone()).isEqualTo("222");
        assertThat(c.getFiscalAddress().getAddress()).isEqualTo("Second");
    }
    @Test void pendingBookAllowsUnchangedCollectionButRejectsReplacement() {
        var c = customer(); c.addressPending(UUID.randomUUID());
        assertThat(CustomerAddressBookService.prepareUpdate(c, c.getAddresses(), null, null, null, null, null))
                .isEqualTo(c.getAddresses());
        assertThatThrownBy(() -> CustomerAddressBookService.prepareUpdate(c, List.of(entry("New", "3")),
                c.getVersion(), null, null, null, null)).hasMessage("CUSTOMER_ADDRESSES_PENDING");
    }
    @Test void delayedUnreceivedOperationCannotBeDiscardedOrOverwriteDraft() {
        var c = customer(); var local = c.getAddresses(); c.addressPending(UUID.randomUUID());
        var remote = new CustomerAddressBookService.Snapshot(UUID.randomUUID(), 2L, List.of(entry("Remote", "4")),
                "NOT_FOUND", c.getAddressOperationId());
        CustomerAddressBookService.applySnapshot(c, remote, false);
        assertThat(c.getAddresses()).isEqualTo(local);
        assertThatThrownBy(() -> CustomerAddressBookService.applySnapshot(c, remote, true)).hasMessage("CUSTOMER_ADDRESSES_PENDING");
    }
    @Test void conflictPreservesDraftUntilExplicitUseCentral() {
        var c = customer(); var local = c.getAddresses(); c.addressPending(UUID.randomUUID());
        var remote = new CustomerAddressBookService.Snapshot(UUID.randomUUID(), 3L, List.of(entry("Remote", "4")),
                "CONFLICT", c.getAddressOperationId());
        CustomerAddressBookService.applySnapshot(c, remote, false);
        assertThat(c.getAddresses()).isEqualTo(local);
        assertThat(c.getAddressSyncStatus()).isEqualTo("CONFLICT");
        CustomerAddressBookService.applySnapshot(c, remote, true);
        assertThat(c.getAddressBookRevision()).isEqualTo(3L);
        assertThat(c.getAddressSyncStatus()).isEqualTo("SYNCED");
        assertThat(c.getPhone()).isEqualTo("4");
    }
    @Test void differingLegacyDataIsNotSilentlyOverwritten() {
        var c = customer(); var local = c.getAddresses();
        CustomerAddressBookService.applySnapshot(c, new CustomerAddressBookService.Snapshot(UUID.randomUUID(), 1L,
                List.of(entry("Remote", "4")), null, null), false);
        assertThat(c.getAddresses()).isEqualTo(local);
        assertThat(c.getAddressSyncStatus()).isEqualTo("CONFLICT");
    }
    @Test void matchingInitialSnapshotConfirmsStableIdsAndRevision() {
        var c = customer(); c.addressPending(null);
        CustomerAddressBookService.applySnapshot(c, new CustomerAddressBookService.Snapshot(UUID.randomUUID(), 1L,
                c.getAddresses(), null, null), false);
        assertThat(c.getAddressSyncStatus()).isEqualTo("SYNCED");
        assertThat(c.getAddressBookRevision()).isEqualTo(1L);
    }
    @Test void duplicateIdsAndInvalidCountryAreRejected() {
        var e = entry("One", "1");
        assertThatThrownBy(() -> CustomerAddressEntry.validated(List.of(e,e))).hasMessage("CUSTOMER_ADDRESSES_INVALID");
        assertThatThrownBy(() -> CustomerAddressEntry.validated(List.of(new CustomerAddressEntry(UUID.randomUUID(),
                new CustomerAddressEntry.Address(null,null,null,null,"12"),null,null,null))))
                .hasMessage("CUSTOMER_ADDRESSES_INVALID");
    }
}
