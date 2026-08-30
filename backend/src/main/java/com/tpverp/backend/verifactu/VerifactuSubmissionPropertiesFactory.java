package com.tpverp.backend.verifactu;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class VerifactuSubmissionPropertiesFactory {

    private final Environment environment;
    private final FiscalRuntimeProperties runtime;

    public VerifactuSubmissionPropertiesFactory(Environment environment) {
        this(environment, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VerifactuSubmissionPropertiesFactory(Environment environment,
            FiscalRuntimeProperties runtime) {
        this.environment = environment;
        this.runtime = runtime;
    }

    public VerifactuSubmissionProperties current() {
        return new VerifactuSubmissionProperties(
                mode(),
                required("tpv.verifactu.system-name"),
                required("tpv.verifactu.system-id"),
                defaulted("tpv.verifactu.producer-name", "TPV ERP DEV"),
                defaulted("tpv.verifactu.producer-tax-id", "B00000000"),
                runtime == null ? defaulted("tpv.verifactu.system-version", "4.2.0")
                        : runtime.systemVersion());
    }
    // Lee la configuracion efectiva desde Spring, incluyendo variables de entorno resueltas.

    private VerifactuEndpointMode mode() {
        return VerifactuEndpointMode.valueOf(required("tpv.verifactu.endpoint-mode"));
    }

    private String required(String key) {
        var value = environment.getProperty(key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " obligatorio");
        }
        return value.trim();
    }

    private String defaulted(String key, String fallback) {
        var value = environment.getProperty(key, fallback);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
