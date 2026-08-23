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
