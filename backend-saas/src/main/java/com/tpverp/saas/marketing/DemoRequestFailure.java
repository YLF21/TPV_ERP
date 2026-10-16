package com.tpverp.saas.marketing;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Safe public error metadata: never include submitted values or database messages. */
final class DemoRequestFailure extends RuntimeException {
    final HttpStatus status;
    final String code;
    final Map<String, String> fields;
    final Long retryAfter;

    DemoRequestFailure(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of(), null);
    }

    DemoRequestFailure(HttpStatus status, String code, String message, Map<String, String> fields, Long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = Map.copyOf(fields);
        this.retryAfter = retryAfter;
    }
}
