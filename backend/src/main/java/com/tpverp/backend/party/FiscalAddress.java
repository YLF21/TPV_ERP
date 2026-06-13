package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;

@Embeddable
public class FiscalAddress {

    @Column(name = "direccion")
    private String address;

    @Column(name = "codigo_postal", length = 16)
    private String postalCode;

    @Column(name = "poblacion", length = 128)
    private String city;

    @Column(name = "provincia", length = 128)
    private String province;

    @Column(name = "pais", length = 2)
    private String country;

    protected FiscalAddress() {
    }

    public FiscalAddress(
            String address,
            String postalCode,
            String city,
            String province,
            String country) {
        this.address = PartyValues.optional(address);
        this.postalCode = PartyValues.optional(postalCode);
        this.city = PartyValues.optional(city);
        this.province = PartyValues.optional(province);
        this.country = normalizeCountry(country);
    }

    public boolean isComplete() {
        // Una factura necesita todos los componentes fiscales, no solo la calle.
        return address != null && postalCode != null && city != null
                && province != null && country != null;
    }

    public String getAddress() {
        return address;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCity() {
        return city;
    }

    public String getProvince() {
        return province;
    }

    public String getCountry() {
        return country;
    }

    private static String normalizeCountry(String value) {
        String country = PartyValues.optional(value);
        if (country == null) {
            return null;
        }
        country = country.toUpperCase(Locale.ROOT);
        if (country.length() != 2) {
            throw new IllegalArgumentException("pais debe tener dos caracteres");
        }
        return country;
    }
}
