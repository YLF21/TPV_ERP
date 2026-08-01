package com.tpverp.backend.document;

/**
 * Per-sale output override. It is operational terminal state, not part of the
 * fiscal content of the resulting commercial document.
 */
public enum SalePrintMode {
    DEFAULT,
    TICKET_PRINTER,
    A4_PRINTER,
    PDF,
    NONE
}
