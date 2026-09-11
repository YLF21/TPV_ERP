package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.List;

/** Informational annual invoice summary, not an AEAT declaration or a fiscal reissue. */
public record CustomerModel347Report(
        int year, Party issuer, Party customer, List<Quarter> quarters, BigDecimal annualTotal) {

    public CustomerModel347Report {
        quarters = List.copyOf(quarters);
    }

    public record Party(String code, String taxId, String name, String address) {
    }

    public record Quarter(int number, BigDecimal total, long documentCount) {
    }
}
