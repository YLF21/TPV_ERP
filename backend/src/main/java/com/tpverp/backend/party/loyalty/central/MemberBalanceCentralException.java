package com.tpverp.backend.party.loyalty.central;

public class MemberBalanceCentralException extends RuntimeException {

    public enum Kind {
        UNAVAILABLE,
        CONFLICT,
        REJECTED,
        UNAUTHORIZED,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final Integer statusCode;

    public MemberBalanceCentralException(Kind kind, String message) {
        this(kind, null, message, null);
    }

    public MemberBalanceCentralException(Kind kind, Integer statusCode, String message) {
        this(kind, statusCode, message, null);
    }

    public MemberBalanceCentralException(Kind kind, String message, Throwable cause) {
        this(kind, null, message, cause);
    }

    protected MemberBalanceCentralException(
            Kind kind,
            Integer statusCode,
            String message,
            Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
