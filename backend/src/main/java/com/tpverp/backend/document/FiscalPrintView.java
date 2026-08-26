package com.tpverp.backend.document;

import com.tpverp.backend.verifactu.FiscalEndpointEnvironment;
import com.tpverp.backend.verifactu.FiscalMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Public, immutable view of the fiscal print snapshot.
 *
 * <p>The values in this record are copied from {@code snapshot_impresion_fiscal};
 * renderers must not derive any of them from the QR URL or from the current
 * fiscal configuration.</p>
 */
public record FiscalPrintView(
        String formatVersion,
        String generatorVersion,
        FiscalMode mode,
        FiscalEndpointEnvironment environment,
        String qrUrl,
        String qrPayloadSha256,
        String prefix,
        String legend,
        String testNotice,
        String issuerName,
        String issuerTaxId,
        Map<String, String> issuerAddress) {

    public FiscalPrintView(
            String formatVersion,
            String generatorVersion,
            FiscalMode mode,
            FiscalEndpointEnvironment environment,
            String qrUrl,
            String qrPayloadSha256,
            String prefix,
            String legend,
            String testNotice) {
        this(formatVersion, generatorVersion, mode, environment, qrUrl,
                qrPayloadSha256, prefix, legend, testNotice, null, null, null);
    }

    public FiscalPrintView {
        Objects.requireNonNull(formatVersion, "formatVersion");
        Objects.requireNonNull(generatorVersion, "generatorVersion");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(qrUrl, "qrUrl");
        Objects.requireNonNull(qrPayloadSha256, "qrPayloadSha256");
        Objects.requireNonNull(prefix, "prefix");
        boolean issuerAbsent = issuerName == null && issuerTaxId == null
                && issuerAddress == null;
        if (!issuerAbsent && (!nonBlank(issuerName) || !nonBlank(issuerTaxId)
                || !validAddress(issuerAddress))) {
            throw new IllegalArgumentException("fiscal_print_issuer_identity_invalid");
        }
        if (issuerAddress != null) {
            issuerAddress = Collections.unmodifiableMap(
                    new LinkedHashMap<>(issuerAddress));
        }
    }

    public boolean hasFrozenIssuerIdentity() {
        return nonBlank(issuerName) && nonBlank(issuerTaxId)
                && validAddress(issuerAddress);
    }

    private static boolean validAddress(Map<String, String> address) {
        if (address == null) {
            return false;
        }
        for (String key : new String[] {
                "linea1", "codigoPostal", "ciudad", "provincia", "pais"}) {
            if (!nonBlank(address.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
