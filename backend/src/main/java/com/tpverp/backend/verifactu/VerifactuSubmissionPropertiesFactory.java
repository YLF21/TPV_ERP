package com.tpverp.backend.verifactu;

import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class VerifactuSubmissionPropertiesFactory {

    private final Environment environment;

    public VerifactuSubmissionPropertiesFactory(Environment environment) {
        this.environment = environment;
    }

    public VerifactuSubmissionProperties current() {
        return new VerifactuSubmissionProperties(
                mode(),
                Path.of(required("tpv.verifactu.certificate-path")),
                required("tpv.verifactu.certificate-password").toCharArray(),
                required("tpv.verifactu.system-name"),
                required("tpv.verifactu.system-id"));
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
}
