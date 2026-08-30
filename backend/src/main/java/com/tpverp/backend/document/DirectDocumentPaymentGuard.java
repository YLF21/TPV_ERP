package com.tpverp.backend.document;

/** Payment methods that may only be applied through the authorized wallet/session flow. */
final class DirectDocumentPaymentGuard {

    private DirectDocumentPaymentGuard() {
    }

    static void requireAllowed(PaymentMethod method) {
        if (method != null && ("SALDO_MIEMBRO".equals(method.getNombre())
                || "CREDITO_DEVOLUCION".equals(method.getNombre()))) {
            throw new IllegalArgumentException("direct_document_payment_method_not_allowed");
        }
    }
}
