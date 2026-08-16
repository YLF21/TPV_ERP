package com.tpverp.backend.document;

/** Prevents cancelling a ticket while one of its generated vouchers is still in use. */
public final class TicketGeneratedVoucherAlreadyUsedException extends IllegalStateException {

    public static final String CODE = "TICKET_GENERATED_VOUCHER_ALREADY_USED";

    public TicketGeneratedVoucherAlreadyUsedException() {
        super(CODE);
    }
}
