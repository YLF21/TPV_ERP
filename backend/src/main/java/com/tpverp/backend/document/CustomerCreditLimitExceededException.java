package com.tpverp.backend.document;

public final class CustomerCreditLimitExceededException extends IllegalStateException {

    public static final String CODE = "CUSTOMER_CREDIT_LIMIT_EXCEEDED";
    public static final String MESSAGE_KEY = "message.document.customer_credit_limit_exceeded";

    public CustomerCreditLimitExceededException() {
        super(MESSAGE_KEY);
    }
}
