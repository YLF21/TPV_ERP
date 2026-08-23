package com.tpverp.backend.verifactu;

import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runtime fiscal boundary. Spring profiles are not treated as fiscal
 * authorization; the explicit properties below are the source of truth.
 */
@Component
public class FiscalRuntimeProperties {

    private final FiscalRuntimeClass runtimeClass;
    private final FiscalEndpointEnvironment endpointEnvironment;
    private final FiscalTransportMode transportMode;
    private final boolean sandboxEnabled;
    private final boolean aeatTestNetworkEnabled;
    private final boolean productionEnabled;
    private final FiscalMode sandboxInitialMode;
    private final String devSigningPkcs12;
    private final String devSigningPassword;
    private final String producerName;
    private final String producerTaxId;
    private final String systemVersion;

    public FiscalRuntimeProperties(Environment environment) {
        runtimeClass = enumValue(environment, "tpv.verifactu.runtime-class",
                FiscalRuntimeClass.REAL);
        endpointEnvironment = enumValue(environment, "tpv.verifactu.endpoint-environment",
                FiscalEndpointEnvironment.TEST);
        transportMode = enumValue(environment, "tpv.verifactu.transport-mode",
                FiscalTransportMode.AEAT);
        sandboxEnabled = Boolean.parseBoolean(environment.getProperty(
                "tpv.verifactu.dev-sandbox.enabled", "false"));
        aeatTestNetworkEnabled = Boolean.parseBoolean(environment.getProperty(
                "tpv.verifactu.aeat-test-network-enabled", "false"));
        productionEnabled = Boolean.parseBoolean(environment.getProperty(
                "tpv.verifactu.production-enabled", "false"));
        sandboxInitialMode = enumValue(environment, "tpv.verifactu.dev-initial-mode",
                FiscalMode.VERIFACTU);
        devSigningPkcs12 = environment.getProperty("tpv.verifactu.dev-signing-pkcs12", "");
        devSigningPassword = environment.getProperty("tpv.verifactu.dev-signing-password", "");
        producerName = environment.getProperty("tpv.verifactu.producer-name", "");
        producerTaxId = environment.getProperty("tpv.verifactu.producer-tax-id", "");
        systemVersion = environment.getProperty("tpv.verifactu.system-version", "");
        validate();
    }

    public FiscalRuntimeClass runtimeClass() {
        return runtimeClass;
    }

    public FiscalEndpointEnvironment endpointEnvironment() {
        return endpointEnvironment;
    }

    public FiscalTransportMode transportMode() {
        return transportMode;
    }

    public boolean sandboxEnabled() {
        return sandboxEnabled;
    }

    public boolean aeatTestNetworkEnabled() {
        return aeatTestNetworkEnabled;
    }

    public boolean productionEnabled() {
        return productionEnabled;
    }

    public boolean isSandbox() {
        return runtimeClass == FiscalRuntimeClass.SANDBOX;
    }

    public boolean isAeatTest() {
        return endpointEnvironment == FiscalEndpointEnvironment.TEST
                && transportMode == FiscalTransportMode.AEAT;
    }

    public FiscalMode sandboxInitialMode() {
        return sandboxInitialMode;
    }

    public String devSigningPkcs12() {
        return devSigningPkcs12;
    }

    public String devSigningPassword() {
        return devSigningPassword;
    }

    /**
     * REAL production can only be enabled after replacing the clearly fictitious
     * laboratory identity with the declared fiscal software identity.
     */
    public void requireProductionIdentity() {
        if (runtimeClass != FiscalRuntimeClass.REAL) {
            return;
        }
        rejectPlaceholder("tpv.verifactu.producer-name", producerName,
                value -> value.toUpperCase(Locale.ROOT).contains("DEV")
                        || value.toUpperCase(Locale.ROOT).contains("TEST")
                        || value.toUpperCase(Locale.ROOT).contains("PLACEHOLDER"));
        rejectPlaceholder("tpv.verifactu.producer-tax-id", producerTaxId,
                value -> value.equalsIgnoreCase("B00000000")
                        || value.equalsIgnoreCase("00000000T"));
        rejectPlaceholder("tpv.verifactu.system-version", systemVersion,
                value -> value.equalsIgnoreCase("0.0.1")
                        || value.toUpperCase(Locale.ROOT).contains("SNAPSHOT"));
    }

    private void validate() {
        if (runtimeClass == FiscalRuntimeClass.SANDBOX && !sandboxEnabled) {
            throw new IllegalStateException(
                    "SANDBOX fiscal requiere tpv.verifactu.dev-sandbox.enabled=true");
        }
        if (runtimeClass == FiscalRuntimeClass.SANDBOX
                && endpointEnvironment == FiscalEndpointEnvironment.PRODUCTION) {
            throw new IllegalStateException("SANDBOX nunca puede usar endpoints de produccion");
        }
        if (endpointEnvironment == FiscalEndpointEnvironment.PRODUCTION && !productionEnabled) {
            throw new IllegalStateException(
                    "PRODUCTION permanece bloqueado hasta superar la validacion fiscal final");
        }
        if (runtimeClass == FiscalRuntimeClass.REAL
                && transportMode == FiscalTransportMode.SIMULATED) {
            throw new IllegalStateException("REAL nunca puede usar transporte simulado");
        }
        if (isAeatTest() && runtimeClass == FiscalRuntimeClass.SANDBOX
                && !aeatTestNetworkEnabled) {
            throw new IllegalStateException(
                    "AEAT TEST en SANDBOX requiere opt-in de red explicito");
        }
        if (runtimeClass == FiscalRuntimeClass.REAL
                && endpointEnvironment == FiscalEndpointEnvironment.PRODUCTION) {
            requireProductionIdentity();
        }
    }

    private static void rejectPlaceholder(String key, String value,
            java.util.function.Predicate<String> predicate) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || predicate.test(normalized)) {
            throw new IllegalStateException(
                    key + " contiene una identidad provisional; se bloquea REAL/PRODUCTION");
        }
    }

    private static <T extends Enum<T>> T enumValue(
            Environment environment, String key, T defaultValue) {
        var value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(defaultValue.getDeclaringClass(), value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(key + " no es valido: " + value, exception);
        }
    }
}
