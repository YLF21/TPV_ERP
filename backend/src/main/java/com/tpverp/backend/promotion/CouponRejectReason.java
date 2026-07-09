package com.tpverp.backend.promotion;

public enum CouponRejectReason {
    NOT_FOUND,
    EXPIRED,
    CANCELLED,
    USED,
    CUSTOMER_MISMATCH,
    DOCUMENT_NOT_ELIGIBLE,
    MINIMUM_NOT_REACHED
}
