package com.tpverp.backend.security.application;

public class TerminalDisabledException extends RuntimeException {
    public static final String CODE = "TERMINAL_DISABLED";

    public TerminalDisabledException() {
        super("message.security.terminal_disabled");
    }
}
