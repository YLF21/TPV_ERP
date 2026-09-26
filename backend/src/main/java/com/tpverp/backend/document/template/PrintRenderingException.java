package com.tpverp.backend.document.template;

/** Identifies an operational rendering failure while preserving the existing conflict response. */
public class PrintRenderingException extends IllegalStateException {
    public PrintRenderingException(String message) { super(message); }
    public PrintRenderingException(String message, Throwable cause) { super(message, cause); }
}
