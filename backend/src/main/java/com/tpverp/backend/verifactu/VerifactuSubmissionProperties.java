package com.tpverp.backend.verifactu;

import java.nio.file.Path;
import java.util.Arrays;

public record VerifactuSubmissionProperties(
        VerifactuEndpointMode mode,
        Path certificatePath,
        char[] certificatePassword,
        String systemName,
        String systemId) {

    // Normaliza los parametros necesarios para preparar el envio certificado a AEAT.
    public VerifactuSubmissionProperties {
        if (mode == null) {
            throw new IllegalArgumentException("modo VERI*FACTU obligatorio");
        }
        if (certificatePath == null) {
            throw new IllegalArgumentException("ruta de certificado obligatoria");
        }
        certificatePassword = password(certificatePassword);
        systemName = required(systemName, "nombre de sistema");
        systemId = required(systemId, "id de sistema");
    }

    @Override
    public char[] certificatePassword() {
        return certificatePassword.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VerifactuSubmissionProperties value
                && mode == value.mode
                && certificatePath.equals(value.certificatePath)
                && Arrays.equals(certificatePassword, value.certificatePassword)
                && systemName.equals(value.systemName)
                && systemId.equals(value.systemId);
    }

    @Override
    public int hashCode() {
        var result = mode.hashCode();
        result = 31 * result + certificatePath.hashCode();
        result = 31 * result + Arrays.hashCode(certificatePassword);
        result = 31 * result + systemName.hashCode();
        result = 31 * result + systemId.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "VerifactuSubmissionProperties[mode=%s, certificatePath=%s, systemName=%s, systemId=%s]"
                .formatted(mode, certificatePath, systemName, systemId);
    }

    private static char[] password(char[] value) {
        var normalized = value == null ? "" : new String(value).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("password del certificado obligatoria");
        }
        return normalized.toCharArray();
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " obligatorio");
        }
        return normalized;
    }
}
