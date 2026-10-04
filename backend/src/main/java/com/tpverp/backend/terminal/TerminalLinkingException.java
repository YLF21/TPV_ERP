package com.tpverp.backend.terminal;

import org.springframework.http.HttpStatus;

public final class TerminalLinkingException extends RuntimeException {
    private final HttpStatus status;
    public TerminalLinkingException(HttpStatus status, String code) { super(code); this.status = status; }
    public HttpStatus status() { return status; }
}
