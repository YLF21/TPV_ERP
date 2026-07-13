package com.tpverp.backend.terminal;

import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentTerminalRecoveryWorker {
    private final PaymentTerminalOperationService operations;
    private final com.tpverp.backend.document.PosCardCheckoutRepository checkouts;
    private final com.tpverp.backend.document.PosCardCheckoutCoordinator coordinator;
    private final com.tpverp.backend.document.PosCardDocumentSnapshot snapshots;
    private final com.tpverp.backend.document.PosCardTicketCreator tickets;
    private final com.tpverp.backend.security.domain.UserAccountRepository users;
    private final com.tpverp.backend.organization.StoreRepository stores;
    private final com.tpverp.backend.document.DocumentPaymentRepository documentPayments;
    private final com.tpverp.backend.document.DocumentService documents;
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentTerminalRecoveryWorker(PaymentTerminalOperationService operations,
            com.tpverp.backend.document.PosCardCheckoutRepository checkouts,
            com.tpverp.backend.document.PosCardCheckoutCoordinator coordinator,
            com.tpverp.backend.document.PosCardDocumentSnapshot snapshots,
            com.tpverp.backend.document.PosCardTicketCreator tickets,
            com.tpverp.backend.security.domain.UserAccountRepository users,
            com.tpverp.backend.organization.StoreRepository stores,
            com.tpverp.backend.document.DocumentPaymentRepository documentPayments,
            com.tpverp.backend.document.DocumentService documents){this.operations=operations;this.checkouts=checkouts;this.coordinator=coordinator;this.snapshots=snapshots;this.tickets=tickets;this.users=users;this.stores=stores;this.documentPayments=documentPayments;this.documents=documents;}
    PaymentTerminalRecoveryWorker(PaymentTerminalOperationService operations,
            com.tpverp.backend.document.PosCardCheckoutRepository checkouts,
            com.tpverp.backend.document.PosCardCheckoutCoordinator coordinator,
            com.tpverp.backend.document.PosCardDocumentSnapshot snapshots,
            com.tpverp.backend.document.PosCardTicketCreator tickets,
            com.tpverp.backend.security.domain.UserAccountRepository users,
            com.tpverp.backend.organization.StoreRepository stores,
            com.tpverp.backend.document.DocumentPaymentRepository documentPayments){this(operations,checkouts,coordinator,snapshots,tickets,users,stores,documentPayments,null);}
    @Scheduled(fixedDelayString="${tpv.payment-terminal.recovery-delay-ms:15000}")
    public void recover(){for(var operation:operations.recoverable(20)){try{operations.recover(operation.getId(),UUID.randomUUID());
    }catch(RuntimeException ignored){/* next bounded recovery cycle */}}
        for(var operation:operations.approvedWithoutDocument(20)){var owner=UUID.randomUUID();try{var claimed=operations.claimApprovedDocument(operation.getId(),owner);
            if(claimed!=null)resumeTicket(claimed);
        }catch(IdentityMismatchException invalid){operations.documentReview(operation.getId(),invalid.getMessage());}
         catch(RuntimeException failure){operations.documentFailure(operation.getId(),"No se pudo reanudar el ticket aprobado");}}
    }

    void resumeTicket(PaymentTerminalOperation operation){
        if(operation.getOperationType()==PaymentTerminalOperationType.REFUND){resumeRefund(operation);return;}
        var checkout=checkouts.findById(operation.getId()).orElse(null);
        if(checkout==null)throw new IllegalStateException("No existe el checkout aprobado que debe documentarse");
        if(checkout.getDocumentId()!=null){reconcileExistingDocument(operation,checkout);return;}
        checkout=coordinator.recoverApproved(operation.getId(),operation.getExternalReference(),operation.getAuthorizationCode(),"Pago recuperado automaticamente");
        var userId=checkout.getRequestedUserId();var storeId=checkout.getRequestedStoreId();var companyId=checkout.getRequestedCompanyId();
        if(userId==null||storeId==null||companyId==null)throw new IdentityMismatchException("Identidad de recuperacion legacy incompleta");
        var store=stores.findById(storeId).orElseThrow(()->new IdentityMismatchException("Tienda de recuperacion inexistente"));
        if(!store.getEmpresa().getId().equals(companyId)||!operation.getStoreId().equals(storeId))throw new IdentityMismatchException("Tienda o empresa no coincide con el cobro");
        var user=users.findById(userId).orElseThrow(()->new IdentityMismatchException("Usuario de recuperacion inexistente"));
        var allowed=user.isActivo()&&user.getTienda()!=null&&user.getTienda().getId().equals(storeId);
        if(!allowed)throw new IdentityMismatchException("Usuario inactivo o sin acceso a la tienda original");
        var owner=UUID.randomUUID();if(!coordinator.claimTicket(checkout.getId(),owner))throw new IllegalStateException("El ticket esta siendo procesado");
        var authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(user,"RECOVERY",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SYSTEM")));
        try{tickets.create(checkout.getId(),snapshots.deserialize(checkout.getDocumentSnapshot()),authentication,checkout.getTerminalId());}
        catch(RuntimeException exception){coordinator.releaseTicket(checkout.getId(),owner);throw exception;}
    }
    private void resumeRefund(PaymentTerminalOperation refund){var original=operations.find(refund.getOriginalOperationId()).orElseThrow();
        if(refund.getAmount().compareTo(original.getAmount())!=0||original.getDocumentId()==null)
            throw new IdentityMismatchException("La devolucion parcial necesita desglose fiscal de lineas");
        var checkout=checkouts.findById(original.getId()).orElseThrow(()->new IdentityMismatchException("No existe identidad original del cobro"));
        var user=users.findById(checkout.getRequestedUserId()).orElseThrow(()->new IdentityMismatchException("Usuario de devolucion inexistente"));
        if(!user.isActivo()||user.getTienda()==null||!user.getTienda().getId().equals(refund.getStoreId()))
            throw new IdentityMismatchException("Usuario sin acceso a la tienda original");
        var authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(user,"RECOVERY",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SYSTEM")));
        var document=documents.createApprovedCardRefund(refund.getId(),original.getDocumentId(),refund.getAmount(),authentication);
        operations.linkDocument(refund.getId(),document.getId(),null);
    }
    private void reconcileExistingDocument(PaymentTerminalOperation operation,com.tpverp.backend.document.PosCardCheckout checkout){var matches=documentPayments
            .findAllByDocumentoId(checkout.getDocumentId()).stream().filter(payment->payment.getPaymentTerminalProvider()==operation.getProvider()
                    && payment.getPaymentTerminalStatus()==PaymentTerminalOperationStatus.APPROVED
                    && operation.getTerminalId().equals(payment.getPaymentTerminalId())
                    && java.util.Objects.equals(operation.getExternalReference(),payment.getReferencia())
                    && java.util.Objects.equals(operation.getAuthorizationCode(),payment.getCardAuthorizationCode())
                    && operation.getAmount().compareTo(payment.getImporte())==0).toList();
        if(matches.size()!=1)throw new IdentityMismatchException("El documento existente no contiene un pago de datafono verificable y unico");
        operations.linkDocument(operation.getId(),checkout.getDocumentId(),matches.getFirst().getId());
    }
    static final class IdentityMismatchException extends RuntimeException{IdentityMismatchException(String message){super(message);}}
}
