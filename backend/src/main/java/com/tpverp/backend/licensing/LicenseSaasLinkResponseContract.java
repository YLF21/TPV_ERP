package com.tpverp.backend.licensing;

import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.organization.StoreFiscalIdentity;
import java.util.Map;

/** Validates the complete response emitted by the current SaaS link endpoint. */
final class LicenseSaasLinkResponseContract {

    private static final String[] ADDRESS_FIELDS = {
            "linea1", "ciudad", "codigoPostal", "provincia", "pais"
    };

    private LicenseSaasLinkResponseContract() {
    }

    static LicenseSaasLinkResponse requireCurrent(LicenseSaasLinkResponse response) {
        if (response == null) {
            throw invalid("Falta response");
        }
        required(response.licenseReference(), "licenseReference");
        required(response.installationToken(), "installationToken");
        required(response.companyId(), "companyId");
        required(response.storeId(), "storeId");
        required(response.validUntil(), "validUntil");
        required(response.status(), "status");
        required(response.taxpayerType(), "taxpayerType");
        required(response.impuestos(), "impuestos");
        required(response.commercialProfile(), "commercialProfile");
        required(response.verifactuActivationDate(), "verifactuActivationDate");
        required(response.verifactuPolicyUpdatedAt(), "verifactuPolicyUpdatedAt");
        if (response.maxWindows() < 1 || response.maxPda() < 0) {
            throw invalid("Los cupos de la licencia no son validos");
        }
        if (response.licenseVersion() < 1) {
            throw invalid("La version de licencia SaaS no es valida");
        }
        if (response.verifactuPolicyVersion() < 0) {
            throw invalid("La version de politica VERI*FACTU no es valida");
        }

        String companyTaxId = fiscalValue(
                () -> SpanishTaxId.validate(required(response.companyTaxId(), "companyTaxId")),
                "companyTaxId");
        String licenseTaxId = fiscalValue(
                () -> SpanishTaxId.validate(required(response.taxId(), "taxId")),
                "taxId");
        if (!companyTaxId.equals(licenseTaxId)) {
            throw invalid("companyTaxId y taxId no coinciden");
        }
        required(response.companyName(), "companyName");
        required(response.storeName(), "storeName");
        fiscalValue(
                () -> StoreFiscalIdentity.code(required(response.storeCode(), "storeCode")),
                "storeCode");
        fiscalValue(
                () -> StoreFiscalIdentity.timezone(required(response.timeZoneId(), "timeZoneId")),
                "timeZoneId");
        requiredAddress(response.companyAddress(), "companyAddress");
        requiredAddress(response.storeAddress(), "storeAddress");
        return response;
    }

    private static void requiredAddress(Map<String, String> address, String field) {
        if (address == null) {
            throw invalid("Falta " + field);
        }
        for (String addressField : ADDRESS_FIELDS) {
            required(address.get(addressField), field + "." + addressField);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("Falta " + field);
        }
        return value.trim();
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw invalid("Falta " + field);
        }
        return value;
    }

    private static String fiscalValue(java.util.function.Supplier<String> supplier, String field) {
        try {
            return supplier.get();
        } catch (LicenseValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LicenseValidationException(
                    "El campo " + field + " de la respuesta SaaS no es valido", exception);
        }
    }

    private static LicenseValidationException invalid(String message) {
        return new LicenseValidationException(message);
    }
}
