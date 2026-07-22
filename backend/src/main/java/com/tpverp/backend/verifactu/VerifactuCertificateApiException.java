package com.tpverp.backend.verifactu;

import java.util.Map;
import org.springframework.http.HttpStatus;

public final class VerifactuCertificateApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    private VerifactuCertificateApiException(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public static VerifactuCertificateApiException badRequest(String code, String message) {
        return new VerifactuCertificateApiException(
                HttpStatus.BAD_REQUEST, code, message, Map.of());
    }

    public static VerifactuCertificateApiException payloadTooLarge(
            String code, String message) {
        return new VerifactuCertificateApiException(
                HttpStatus.PAYLOAD_TOO_LARGE, code, message, Map.of());
    }

    public static VerifactuCertificateApiException conflict(String code, String message) {
        return new VerifactuCertificateApiException(
                HttpStatus.CONFLICT, code, message, Map.of());
    }

    public static VerifactuCertificateApiException conflict(
            String code,
            String message,
            Map<String, Object> properties) {
        return new VerifactuCertificateApiException(
                HttpStatus.CONFLICT, code, message, properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
