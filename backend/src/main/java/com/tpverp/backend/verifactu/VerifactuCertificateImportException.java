package com.tpverp.backend.verifactu;

import java.util.LinkedHashSet;
import java.util.List;

public final class VerifactuCertificateImportException extends IllegalArgumentException {

    public enum Failure {
        PASSWORD_OR_FILE_INVALID(
                "CERTIFICATE_PASSWORD_OR_FILE_INVALID",
                "message.verifactu.certificate.password_or_file_invalid"),
        TAX_ID_MISMATCH(
                "CERTIFICATE_TAX_ID_MISMATCH",
                "message.verifactu.certificate.tax_id_mismatch"),
        TAX_ID_MISSING_OR_INVALID(
                "CERTIFICATE_TAX_ID_MISSING_OR_INVALID",
                "message.verifactu.certificate.tax_id_missing_or_invalid"),
        EXPIRED(
                "CERTIFICATE_EXPIRED",
                "message.verifactu.certificate.expired"),
        NOT_YET_VALID(
                "CERTIFICATE_NOT_YET_VALID",
                "message.verifactu.certificate.not_yet_valid"),
        PRIVATE_KEY_MISSING(
                "CERTIFICATE_PRIVATE_KEY_MISSING",
                "message.verifactu.certificate.private_key_missing"),
        MULTIPLE_PRIVATE_KEYS(
                "CERTIFICATE_MULTIPLE_PRIVATE_KEYS",
                "message.verifactu.certificate.multiple_private_keys"),
        CERTIFICATE_CHAIN_INVALID(
                "CERTIFICATE_CHAIN_INVALID",
                "message.verifactu.certificate.chain_invalid"),
        KEY_PAIR_MISMATCH(
                "CERTIFICATE_KEY_PAIR_MISMATCH",
                "message.verifactu.certificate.key_pair_mismatch"),
        KEY_ALGORITHM_UNSUPPORTED(
                "CERTIFICATE_KEY_ALGORITHM_UNSUPPORTED",
                "message.verifactu.certificate.key_algorithm_unsupported"),
        PRIVATE_KEY_ENCODING_INVALID(
                "CERTIFICATE_PRIVATE_KEY_ENCODING_INVALID",
                "message.verifactu.certificate.private_key_encoding_invalid"),
        STRUCTURE_INVALID(
                "CERTIFICATE_STRUCTURE_INVALID",
                "message.verifactu.certificate.structure_invalid");

        private final String apiCode;
        private final String messageKey;

        Failure(String apiCode, String messageKey) {
            this.apiCode = apiCode;
            this.messageKey = messageKey;
        }

        public String apiCode() {
            return apiCode;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final List<Failure> failures;

    private VerifactuCertificateImportException(List<Failure> failures, Throwable cause) {
        super("message.verifactu.certificate.validation_failed", cause);
        var unique = new LinkedHashSet<>(failures);
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("Debe existir al menos un error de certificado");
        }
        this.failures = List.copyOf(unique);
    }

    public static VerifactuCertificateImportException of(Failure failure) {
        return new VerifactuCertificateImportException(List.of(failure), null);
    }

    public static VerifactuCertificateImportException of(Failure failure, Throwable cause) {
        return new VerifactuCertificateImportException(List.of(failure), cause);
    }

    public static VerifactuCertificateImportException of(List<Failure> failures) {
        return new VerifactuCertificateImportException(failures, null);
    }

    public Failure failure() {
        return failures.getFirst();
    }

    public List<Failure> failures() {
        return failures;
    }
}
