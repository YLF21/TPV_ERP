package com.tpverp.backend.terminal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentTerminalOperationService {
    private static final Duration LEASE=Duration.ofSeconds(30);
    private static final int MAX_RECOVERY_ATTEMPTS=5;
    private final PaymentTerminalOperationRepository operations;
    private final CardTerminalConfigurationReader configurations;
    private final List<CardTerminalGateway> gateways;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate chargeTransactions;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentTerminalOperationService(PaymentTerminalOperationRepository operations,
            CardTerminalConfigurationReader configurations,List<CardTerminalGateway> gateways,Clock clock,
            org.springframework.transaction.PlatformTransactionManager transactionManager){
        this.operations=operations;this.configurations=configurations;this.gateways=List.copyOf(gateways);this.clock=clock;
        this.transactions=new TransactionTemplate(transactionManager);
        this.chargeTransactions=new TransactionTemplate(transactionManager);
        this.chargeTransactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    PaymentTerminalOperationService(PaymentTerminalOperationRepository operations,CardTerminalConfigurationReader configurations,
            List<CardTerminalGateway> gateways,Clock clock){this.operations=operations;this.configurations=configurations;this.gateways=List.copyOf(gateways);this.clock=clock;this.transactions=null;this.chargeTransactions=null;}

    public PaymentTerminalResult charge(UUID operationId,String requestHash,BigDecimal amount,
            CardTerminalConfiguration configuration){
        Reservation reserved;
        try { reserved=chargeTx(()->reserveAndMarkSent(operationId,requestHash,amount,configuration)); }
        catch(org.springframework.dao.DataIntegrityViolationException race){reserved=chargeTx(()->existingReservation(operationId,requestHash,configuration));}
        if(!reserved.send()) return toResult(reserved.operation());
        var gateway=resolve(configuration);
        PaymentTerminalResult result;
        try { result=gateway.charge(new PaymentTerminalChargeCommand(operationId,amount),context(configuration,operationId)); }
        catch(RuntimeException exception){ result=new PaymentTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,
                "TRANSPORT_TIMEOUT",null,null,"Resultado incierto del datafono; consulte el estado"); }
        var completed=result;
        return chargeTx(()->recordResult(operationId,completed));
    }

    public Reservation reserveAndMarkSent(UUID id,String requestHash,BigDecimal amount,CardTerminalConfiguration c){
        var existing=operations.findByTerminalIdAndIdempotencyKey(c.terminalId(),id.toString());
        if(existing.isPresent()){
            var operation=existing.orElseThrow();
            if(!operation.getRequestHash().equals(requestHash))throw new IllegalStateException("La clave idempotente pertenece a otra venta");
            return new Reservation(operation,false);
        }
        var operation=PaymentTerminalOperation.reserve(id,c.terminalId(),c.storeId(),c.provider(),
                c.testMode()?PaymentTerminalMode.SIMULATED:PaymentTerminalMode.LIVE,
                PaymentTerminalOperationType.CHARGE,null,id.toString(),requestHash,amount,
                c.configurationHash(),c.configurationVersion(),clock.instant());
        operation.markSent("GATEWAY_SEND",clock.instant());
        return new Reservation(operations.saveAndFlush(operation),true);
    }
    private Reservation existingReservation(UUID id,String requestHash,CardTerminalConfiguration c){var operation=operations
            .findByTerminalIdAndIdempotencyKey(c.terminalId(),id.toString()).orElseThrow();
        if(!operation.getRequestHash().equals(requestHash))throw new IllegalStateException("La clave idempotente pertenece a otra venta");
        return new Reservation(operation,false);}

    public PaymentTerminalResult recordResult(UUID id,PaymentTerminalResult result){
        var operation=operations.findLockedById(id).orElseThrow();
        apply(operation,result,clock.instant());operations.save(operation);return toResult(operation);
    }

    public PaymentTerminalOperation recover(UUID id,UUID owner){
        var claimed=tx(()->claim(id,owner)); if(claimed==null)return operations.findById(id).orElseThrow();
        var configuration=configurations.required(claimed.getTerminalId());
        if(!claimed.matchesConfigurationIdentity(configuration)){
            return tx(()->review(id,"CONFIGURATION_CHANGED","La configuracion del datafono ha cambiado"));
        }
        var gateway=resolve(configuration);
        if(!gateway.capabilities().contains(PaymentTerminalCapability.QUERY))return tx(()->review(id,"QUERY_UNAVAILABLE","El proveedor no permite consultar la operacion"));
        PaymentTerminalResult result;
        var reference=claimed.getExternalReference()==null?id.toString():claimed.getExternalReference();
        try { result=gateway.query(new PaymentTerminalQueryCommand(id,reference),context(configuration,id)); }
        catch(RuntimeException exception){return tx(()->scheduleOrReview(id,"QUERY_TRANSPORT_ERROR","No se pudo consultar el datafono"));}
        if(result.status()==PaymentTerminalOperationStatus.PENDING||result.status()==PaymentTerminalOperationStatus.TIMEOUT)
            return tx(()->scheduleOrReview(id,result.code(),result.message()));
        tx(()->recordQueryResult(id,result));return operations.findById(id).orElseThrow();
    }

    PaymentTerminalOperation claim(UUID id,UUID owner){var op=operations.findLockedById(id).orElseThrow();
        if(!java.util.Set.of(PaymentTerminalOperationStatus.PENDING,PaymentTerminalOperationStatus.SENT,
                PaymentTerminalOperationStatus.TIMEOUT,PaymentTerminalOperationStatus.ERROR).contains(op.getStatus())
                || (op.getStatus()==PaymentTerminalOperationStatus.ERROR&&op.isFinalOutcome()))return null;
        var now=clock.instant();return op.claimProcessing(owner,now.plus(LEASE),now)?operations.save(op):null;}
    PaymentTerminalOperation scheduleOrReview(UUID id,String code,String message){var op=operations.findLockedById(id).orElseThrow();var now=clock.instant();
        if(op.getRetryCount()+1>=MAX_RECOVERY_ATTEMPTS){op.markReviewRequired(code,message,now);op.releaseProcessing(now);}
        else op.scheduleRetry(now.plusSeconds(Math.min(300,1L<<(op.getRetryCount()+1))),now);
        return operations.save(op);}
    PaymentTerminalOperation review(UUID id,String code,String message){var op=operations.findLockedById(id).orElseThrow();var now=clock.instant();op.markReviewRequired(code,message,now);op.releaseProcessing(now);return operations.save(op);}

    PaymentTerminalResult recordQueryResult(UUID id,PaymentTerminalResult result){var operation=operations.findLockedById(id).orElseThrow();var now=clock.instant();
        switch(result.status()){
            case CANCELLED->{ if(operation.getOperationType()==PaymentTerminalOperationType.VOID){
                    operation.voidApprovedFromQuery(result.reference(),result.authorization(),now);
                    var original=operations.findLockedById(operation.getOriginalOperationId()).orElseThrow();original.recordVoid(now);operations.save(original);
                } else operation.declineFromQuery("PAYMENT_REFUND_CANCELLED",result.message(),now); }
            case APPROVED,REFUNDED,PARTIALLY_REFUNDED->{
                if(operation.getOperationType()==PaymentTerminalOperationType.VOID) operation.voidApprovedFromQuery(result.reference(),result.authorization(),now);
                else operation.approveFromQuery(result.reference(),result.authorization(),now);
                if(operation.getOperationType()==PaymentTerminalOperationType.REFUND){var original=operations.findLockedById(operation.getOriginalOperationId()).orElseThrow();
                    original.recordRefund(operation.getAmount(),now);operations.save(original);}
                if(operation.getOperationType()==PaymentTerminalOperationType.VOID){var original=operations.findLockedById(operation.getOriginalOperationId()).orElseThrow();
                    original.recordVoid(now);operations.save(original);}
            }
            case DECLINED->operation.declineFromQuery(result.code(),result.message(),now);
            case ERROR->operation.fail(result.code(),result.message(),result.finalOutcome(),now);
            case REVIEW_REQUIRED->operation.markReviewRequired(result.code(),result.message(),now);
            default->throw new IllegalArgumentException("Resultado de consulta no terminal: "+result.status());
        }
        operation.releaseProcessing(now);
        operations.save(operation);return toResult(operation);}

    public List<PaymentTerminalOperation> recoverable(int limit){var now=clock.instant();return operations.findRecoverable(now,now.minusSeconds(45),org.springframework.data.domain.PageRequest.of(0,limit));}
    public List<PaymentTerminalOperation> approvedWithoutDocument(int limit){return operations.findApprovedWithoutDocument(clock.instant(),org.springframework.data.domain.PageRequest.of(0,limit));}
    public PaymentTerminalOperation claimApprovedDocument(UUID id,UUID owner){return tx(()->claim(id,owner));}
    public PaymentTerminalOperation documentFailure(UUID id,String diagnostic){return tx(()->{var operation=operations.findLockedById(id).orElseThrow();var now=clock.instant();
        if(operation.getDocumentRetryCount()+1>=MAX_RECOVERY_ATTEMPTS){operation.markDocumentReviewRequired("DOCUMENT_RETRY_EXHAUSTED",diagnostic,now);operation.releaseProcessing(now);}
        else operation.scheduleDocumentRetry(now.plusSeconds(Math.min(300,1L<<(operation.getDocumentRetryCount()+1))),now);return operations.save(operation);});}
    public PaymentTerminalOperation documentReview(UUID id,String diagnostic){return tx(()->{var operation=operations.findLockedById(id).orElseThrow();var now=clock.instant();
        operation.markDocumentReviewRequired("DOCUMENT_IDENTITY_INVALID",diagnostic,now);operation.releaseProcessing(now);return operations.save(operation);});}
    public java.util.Optional<PaymentTerminalOperation> find(UUID id){return operations.findById(id);}
    @Transactional(readOnly=true)
    public java.util.Optional<PaymentTerminalOperation> findByDocumentPaymentId(UUID paymentId){
        return operations.findByDocumentPaymentId(paymentId);
    }
    public PaymentTerminalOperation requireFinalizableApprovedCharge(UUID id){return tx(()->{var operation=operations.findLockedById(id).orElseThrow(()->new IllegalStateException("payment_operation_not_finalizable"));if(operation.getOperationType()!=PaymentTerminalOperationType.CHARGE||operation.getStatus()!=PaymentTerminalOperationStatus.APPROVED||operation.getRefundedAmount().signum()!=0||operation.getDocumentId()!=null)throw new IllegalStateException("payment_operation_not_finalizable");return operation;});}
    public void linkDocument(UUID operationId,UUID documentId,UUID paymentId){tx(()->{var operation=operations.findLockedById(operationId).orElseThrow();
        if(operation.getDocumentId()==null){var now=clock.instant();operation.linkDocument(documentId,paymentId,now);operation.releaseProcessing(now);operations.save(operation);}return operation;});}

    private CardTerminalGateway resolve(CardTerminalConfiguration c){return gateways.stream().filter(g->g.supports(c.provider(),c.testMode())).findFirst()
            .orElseThrow(()->new IllegalStateException("No hay un conector disponible para "+c.provider()));}
    private PaymentTerminalGatewayContext context(CardTerminalConfiguration c,UUID id){return new PaymentTerminalGatewayContext(c.terminalId(),c.provider(),
            c.testMode()?PaymentTerminalMode.SIMULATED:PaymentTerminalMode.LIVE,"EUR",id.toString(),c.configurationReference(),
            c.configurationVersion(),c.configurationHash(),c.parameters());}
    private static void apply(PaymentTerminalOperation op,PaymentTerminalResult r,Instant now){switch(r.status()){
        case APPROVED->op.approve(r.reference(),r.authorization(),now); case DECLINED->op.decline(r.code(),r.message(),now);
        case TIMEOUT,PENDING->op.timeout(r.code(),r.message(),now); case ERROR->op.fail(r.code(),r.message(),r.finalOutcome(),now);
        case REVIEW_REQUIRED->op.markReviewRequired(r.code(),r.message(),now);
        default->op.fail("UNEXPECTED_STATUS","Estado inesperado del proveedor: "+r.status(),now);}}
    private static PaymentTerminalResult toResult(PaymentTerminalOperation op){return new PaymentTerminalResult(op.getStatus(),"REPLAY",
            op.getExternalReference(),op.getAuthorizationCode(),"Operacion recuperada por idempotencia",op.isFinalOutcome());}
    private <T> T tx(java.util.function.Supplier<T> work){return transactions==null?work.get():transactions.execute(status->work.get());}
    private <T> T chargeTx(java.util.function.Supplier<T> work){return chargeTransactions==null?work.get():chargeTransactions.execute(status->work.get());}
    public record Reservation(PaymentTerminalOperation operation,boolean send){}
}
