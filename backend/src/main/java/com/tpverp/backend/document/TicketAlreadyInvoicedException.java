package com.tpverp.backend.document;

/** Indicates that a ticket already has an invoice linked to it. */
public final class TicketAlreadyInvoicedException extends IllegalStateException {

    public static final String CODE = "TICKET_ALREADY_INVOICED";
    public static final String MESSAGE_KEY = "message.document.ticket_already_invoiced";

    public TicketAlreadyInvoicedException() {
        super(MESSAGE_KEY);
    }
}
