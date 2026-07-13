package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCardTicketCreator {
    private final DocumentService documents;
    private final PosCardCheckoutRepository checkouts;
    private final com.tpverp.backend.terminal.PaymentTerminalOperationService operations;
    @org.springframework.beans.factory.annotation.Autowired
    public PosCardTicketCreator(DocumentService documents,PosCardCheckoutRepository checkouts,
            com.tpverp.backend.terminal.PaymentTerminalOperationService operations){
        this.documents=documents;this.checkouts=checkouts;this.operations=operations;
    }
    PosCardTicketCreator(DocumentService documents,PosCardCheckoutRepository checkouts){this.documents=documents;this.checkouts=checkouts;this.operations=null;}

    @Transactional
    public PosCardCheckout create(UUID checkoutId,ApprovedCardTicketSnapshot snapshot,Authentication authentication,UUID terminalId){
        var checkout=checkouts.findById(checkoutId).orElseThrow();
        if(checkout.getDocumentId()!=null)return checkout;
        if(checkout.getStatus()!=PaymentTerminalOperationStatus.APPROVED)throw new IllegalStateException("El pago no esta aprobado");
        var stored=checkout.toResult();
        var operation=operations==null?java.util.Optional.<com.tpverp.backend.terminal.PaymentTerminalOperation>empty():operations.find(checkoutId);
        var provider=operation.map(com.tpverp.backend.terminal.PaymentTerminalOperation::getProvider).orElse(PaymentTerminalProvider.REDSYS_TPV_PC);
        var ticket=documents.createApprovedCardTicketFromSnapshot(snapshot,List.of(new PaymentCommand(snapshot.paymentMethodId(),checkout.getTotal(),true,null,null,null,
                stored.reference(),PaymentCardMode.INTEGRATED,provider,
                PaymentTerminalOperationStatus.APPROVED,stored.authorization(),terminalId)),authentication);
        checkout.linkDocument(ticket.getId(),ticket.getNumero(),Instant.now());
        if(operations!=null && !ticket.getPagos().isEmpty())operations.linkDocument(checkoutId,ticket.getId(),ticket.getPagos().getFirst().getId());
        return checkouts.save(checkout);
    }
}
