package com.tpverp.backend.document;

import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerAddressEntry;
import com.tpverp.backend.party.FiscalAddress;
import java.util.UUID;

/** Contact selected for this document, frozen independently of the customer address book. */
public record DocumentCustomerContact(UUID id, CustomerAddressEntry.Address address, String phone, String email) {
    public DocumentCustomerContact {
        if (id == null) throw new IllegalArgumentException("customer_address_invalid");
    }

    public static DocumentCustomerContact resolve(Customer customer, UUID addressId) {
        if (addressId == null) return null;
        if (customer == null) throw new IllegalArgumentException("customer_address_customer_required");
        var entry = customer.getAddresses().stream().filter(value -> addressId.equals(value.id()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("customer_address_not_found"));
        return new DocumentCustomerContact(entry.id(), entry.address(), entry.phone(), entry.email());
    }

    static DocumentCustomerContact retainedFor(CommercialDocument source, DocumentCommand replacement) {
        if (replacement.customerContactSnapshot() != null) return replacement.customerContactSnapshot();
        if (source.getCustomerContact() == null || !java.util.Objects.equals(source.getClienteId(), replacement.clienteId())) return null;
        return replacement.customerAddressId() == null || replacement.customerAddressId().equals(source.getCustomerAddressId())
                ? source.getCustomerContact() : null;
    }

    public FiscalAddress fiscalAddress() {
        return address == null ? null : new FiscalAddress(address.address(), address.postalCode(),
                address.city(), address.province(), address.country());
    }

    public static FiscalAddress addressFor(CommercialDocument document, Customer customer) {
        return document.getCustomerContact() == null ? customer.getFiscalAddress()
                : document.getCustomerContact().fiscalAddress();
    }

    public static String phoneFor(CommercialDocument document, Customer customer) {
        return document.getCustomerContact() == null ? customer.getPhone() : document.getCustomerContact().phone();
    }
}
