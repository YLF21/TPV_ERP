package com.tpverp.backend.organization;

public record CompanyPrintIdentityView(
        String name,
        String taxId,
        Address address) {

    public static CompanyPrintIdentityView from(Company company) {
        var value = company.getDomicilioFiscal();
        return new CompanyPrintIdentityView(
                company.getRazonSocial(),
                company.getTaxId(),
                new Address(
                        value.get("linea1"),
                        value.get("codigoPostal"),
                        value.get("ciudad"),
                        value.get("provincia"),
                        value.get("pais")));
    }

    public record Address(
            String line1,
            String postalCode,
            String city,
            String province,
            String country) {
    }
}
