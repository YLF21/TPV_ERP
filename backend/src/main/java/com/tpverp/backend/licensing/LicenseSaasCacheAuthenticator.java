package com.tpverp.backend.licensing;

import com.tpverp.backend.licensing.application.LicenseValidationException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticates the local SaaS license snapshot with the machine-protected
 * installation token. PostgreSQL remains the cache, never the trust anchor.
 */
public class LicenseSaasCacheAuthenticator {

    public static final int AUTHENTICATED_FORMAT_VERSION = 6;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DOMAIN = "TPV-ERP-SAAS-LICENSE-CACHE-V6";
    private static final String LEGACY_V5_DOMAIN = "TPV-ERP-SAAS-LICENSE-CACHE-V5";

    private final LicenseSaasCredentialStore credentials;

    public LicenseSaasCacheAuthenticator(LicenseSaasCredentialStore credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    public void seal(License license) {
        Objects.requireNonNull(license, "license");
        String token = requiredStoredToken();
        license.authenticateSaasCache(mac(license, token, DOMAIN, true));
    }

    public boolean isAuthentic(License license) {
        if (license == null || license.getFormatVersion() != AUTHENTICATED_FORMAT_VERSION) {
            return false;
        }
        try {
            String token = credentials.readToken().orElse(null);
            if (token == null) {
                return false;
            }
            byte[] expected = mac(license, token, DOMAIN, true)
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] actual = license.getHash().getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void requireRefreshable(License license) {
        Objects.requireNonNull(license, "license");
        if (license.getFormatVersion() == AUTHENTICATED_FORMAT_VERSION) {
            if (!isAuthentic(license)) {
                throw new LicenseValidationException(
                        "El cache local de la licencia SaaS no es autentico o falta su credencial");
            }
            return;
        }
        if (license.getFormatVersion() == 5) {
            String token = requiredStoredToken();
            if (!legacyV5MacMatches(license, token)) {
                throw new LicenseValidationException(
                        "El cache local legacy de la licencia SaaS no es autentico");
            }
            return;
        }
        if (license.getFormatVersion() == 4 && license.isSaasLinked()) {
            requiredStoredToken();
            return;
        }
        throw new LicenseValidationException(
                "La licencia SaaS local no tiene un formato de cache admitido");
    }

    private String requiredStoredToken() {
        try {
            return credentials.readToken()
                    .filter(token -> !token.isBlank())
                    .orElseThrow(() -> new LicenseValidationException(
                            "Falta el token protegido de la instalacion SaaS"));
        } catch (LicenseValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LicenseValidationException(
                    "No se pudo leer el token protegido de la instalacion SaaS",
                    exception);
        }
    }

    private String mac(
            License license,
            String token,
            String domain,
            boolean includeActive) {
        return mac(license, token, domain, includeActive, Map.of());
    }

    private String mac(
            License license,
            String token,
            String domain,
            boolean includeActive,
            Map<String, Instant> instantOverrides) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(
                    canonical(license, domain, includeActive, instantOverrides)));
        } catch (LicenseValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LicenseValidationException(
                    "No se pudo autenticar el cache local de licencia SaaS",
                    exception);
        }
    }

    private byte[] canonical(License license, String domain, boolean includeActive) {
        return canonical(license, domain, includeActive, Map.of());
    }

    private byte[] canonical(
            License license,
            String domain,
            boolean includeActive,
            Map<String, Instant> instantOverrides) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            field(output, "domain", domain);
            field(output, "licenseId", license.getId());
            field(output, "localCompanyId", license.getLocalCompanyId());
            field(output, "localStoreId", license.getTiendaId());
            field(output, "saasCompanyId", required(license.getSaasCompanyId(), "saasCompanyId"));
            field(output, "saasStoreId", required(license.getSaasStoreId(), "saasStoreId"));
            field(output, "licenseReference", license.getReferencia());
            field(output, "installationId", license.getInstalacionId());
            field(output, "installationReference", license.getInstalacionReferencia());
            field(output, "installationPublicKey", license.getInstalacionPublicKey());
            field(output, "validFrom", instant(instantOverrides.getOrDefault(
                    "validFrom", license.getValidaDesde())));
            field(output, "validUntil", instant(instantOverrides.getOrDefault(
                    "validUntil", license.getValidaHasta())));
            field(output, "saasStatus", license.getEstadoSaas());
            field(output, "lastSaasValidationAt", instant(instantOverrides.getOrDefault(
                    "lastSaasValidationAt", license.getUltimaValidacionSaas())));
            field(output, "maxWindows", license.getMaxWindows());
            field(output, "maxPda", license.getMaxPda());
            field(output, "saasLicenseVersion", required(
                    license.getSaasLicenseVersion(), "saasLicenseVersion"));
            field(output, "taxId", license.getTaxId());
            field(output, "taxpayerType", license.getTaxpayerType());
            field(output, "taxRegime", license.getRegimenImpuesto());
            field(output, "commercialProfile", license.getCommercialProfile());
            field(output, "verifactuActivationDate", license.getVerifactuActivationDate());
            field(output, "verifactuPolicyVersion", license.getVerifactuPolicyVersion());
            field(output, "verifactuPolicyUpdatedAt", instant(instantOverrides.getOrDefault(
                    "verifactuPolicyUpdatedAt", license.getVerifactuPolicyUpdatedAt())));
            field(output, "importedAt", instant(instantOverrides.getOrDefault(
                    "importedAt", license.getImportadaEn())));
            if (includeActive) {
                field(output, "active", license.isActiva());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (LicenseValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LicenseValidationException(
                    "No se pudo canonicalizar el cache local de licencia SaaS",
                    exception);
        }
    }

    /**
     * PostgreSQL timestamp columns use microsecond precision and round values
     * on write. Format 5 signed the truncated Java value before persistence,
     * so a legitimate row can contain the next microsecond. Only the five
     * Instant fields present in the legacy canonical payload are varied and
     * each has exactly two candidates: persisted value or persisted value minus
     * one microsecond. This is 2^5 at most and does not relax authentication of
     * any non-temporal field.
     */
    private boolean legacyV5MacMatches(License license, String token) {
        String actualHash = license.getHash();
        if (actualHash == null) {
            return false;
        }
        String[] names = {
            "validFrom",
            "validUntil",
            "lastSaasValidationAt",
            "verifactuPolicyUpdatedAt",
            "importedAt"
        };
        Instant[] values = {
            license.getValidaDesde(),
            license.getValidaHasta(),
            license.getUltimaValidacionSaas(),
            license.getVerifactuPolicyUpdatedAt(),
            license.getImportadaEn()
        };
        int present = 0;
        for (Instant value : values) {
            if (value != null) {
                present++;
            }
        }
        int[] presentIndexes = new int[present];
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                presentIndexes[index++] = i;
            }
        }
        int candidates = 1 << present;
        byte[] actual = actualHash.getBytes(StandardCharsets.US_ASCII);
        for (int mask = 0; mask < candidates; mask++) {
            Map<String, Instant> overrides = new HashMap<>();
            for (int bit = 0; bit < present; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    int valueIndex = presentIndexes[bit];
                    overrides.put(names[valueIndex], values[valueIndex].minus(1, ChronoUnit.MICROS));
                }
            }
            byte[] expected = mac(license, token, LEGACY_V5_DOMAIN, false, overrides)
                    .getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, actual)) {
                return true;
            }
        }
        return false;
    }

    private static void field(DataOutputStream output, String name, Object value)
            throws java.io.IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        output.writeInt(nameBytes.length);
        output.write(nameBytes);
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] valueBytes = value.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(valueBytes.length);
        output.write(valueBytes);
    }

    private static Instant instant(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new LicenseValidationException(
                    "Falta " + field + " en el cache local de licencia SaaS");
        }
        return value;
    }
}
