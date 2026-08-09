package com.tpverp.backend.document;

public final class TicketNotFoundException extends RuntimeException {

    public static final String CODE = "TICKET_NOT_FOUND";

    public TicketNotFoundException() {
        super("message.document.ticket_not_found");
    }
}
