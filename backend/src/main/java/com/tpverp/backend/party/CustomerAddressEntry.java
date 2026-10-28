package com.tpverp.backend.party;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Value owned by the customer's shared address book; order determines the default. */
public record CustomerAddressEntry(UUID id, Address address, String phone, String email, String notes) {
    public record Address(String address, String postalCode, String city, String province, String country) {
        FiscalAddress local() { return new FiscalAddress(address, postalCode, city, province, country); }
        static Address from(FiscalAddress value) {
            return value == null ? new Address(null, null, null, null, null) : new Address(value.getAddress(), value.getPostalCode(), value.getCity(),
                    value.getProvince(), value.getCountry());
        }
    }

    static CustomerIdentityException failure(String suffix) {
        return new CustomerIdentityException("CUSTOMER_ADDRESSES_" + suffix,
                suffix.equals("INVALID") ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.CONFLICT);
    }

    public static List<CustomerAddressEntry> validated(List<CustomerAddressEntry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > 50) throw failure("INVALID");
        var ids = new HashSet<UUID>();
        return entries.stream().map(entry -> {
            if (entry == null || entry.id() == null || !ids.add(entry.id())) throw failure("INVALID");
            var a = entry.address();
            Address address = a == null ? new Address(null, null, null, null, null) : new Address(text(a.address(), 255), text(a.postalCode(), 16),
                    text(a.city(), 128), text(a.province(), 128), country(a.country()));
            return new CustomerAddressEntry(entry.id(), address, text(entry.phone(), 64),
                    text(entry.email(), 320), text(entry.notes(), 65536));
        }).toList();
    }

    private static String country(String value) {
        String result = text(value, 2);
        if (result != null && !result.matches("[A-Za-z]{2}")) throw failure("INVALID");
        return result == null ? null : result.toUpperCase(java.util.Locale.ROOT);
    }
    private static String text(String value, int maximum) {
        String result = PartyValues.optional(value);
        if (result != null && result.length() > maximum) throw failure("INVALID");
        return result;
    }
    static CustomerAddressEntry from(Customer customer) {
        return new CustomerAddressEntry(customer.getId(), Address.from(customer.getFiscalAddress()),
                customer.getPhone(), customer.getEmail(), customer.getNotes());
    }
    boolean sameContact(CustomerAddressEntry other) {
        return java.util.Objects.equals(address, other.address) && java.util.Objects.equals(phone, other.phone)
                && java.util.Objects.equals(email, other.email) && java.util.Objects.equals(notes, other.notes);
    }
}
