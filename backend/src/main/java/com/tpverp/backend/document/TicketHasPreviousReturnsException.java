package com.tpverp.backend.document;

/** Indicates that a partially returned ticket cannot be cancelled in full. */
public final class TicketHasPreviousReturnsException extends IllegalStateException {

    public static final String CODE = "TICKET_HAS_PREVIOUS_RETURNS";
    public static final String MESSAGE_KEY = "message.document.ticket_has_previous_returns";

    public TicketHasPreviousReturnsException() {
        super(MESSAGE_KEY);
    }
}
