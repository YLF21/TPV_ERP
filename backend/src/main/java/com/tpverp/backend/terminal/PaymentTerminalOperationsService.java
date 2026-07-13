package com.tpverp.backend.terminal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;

@Service
public class PaymentTerminalOperationsService {
    private final PaymentTerminalOperationRepository operations;
    private final PaymentTerminalAdjustmentService adjustments;
    private final PaymentTerminalOperationService recovery;
    private final CardTerminalConfigurationReader configurations;
    private final List<CardTerminalGateway> gateways;
    private final Clock clock;
    private final com.tpverp.backend.organization.CurrentOrganization organization;
    private final PaymentTerminalReceiptRepository receipts;
    private final PaymentTerminalReconciliationService reconciliations;
    private final com.tpverp.backend.document.DocumentService documents;

    public PaymentTerminalOperationsService(PaymentTerminalOperationRepository operations,
            PaymentTerminalAdjustmentService adjustments, PaymentTerminalOperationService recovery,
            CardTerminalConfigurationReader configurations, List<CardTerminalGateway> gateways, Clock clock,
            com.tpverp.backend.organization.CurrentOrganization organization,
            PaymentTerminalReceiptRepository receipts,
            PaymentTerminalReconciliationService reconciliations,
            com.tpverp.backend.document.DocumentService documents) {
        this.operations=operations; this.adjustments=adjustments; this.recovery=recovery;
        this.configurations=configurations; this.gateways=List.copyOf(gateways); this.clock=clock; this.organization=organization;
        this.receipts=receipts;this.reconciliations=reconciliations;
        this.documents=documents;
    }

    @Transactional(readOnly=true)
    public PaymentTerminalOperation get(UUID id){ var operation=operations.findById(id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND,"PAYMENT_OPERATION_NOT_FOUND","Operacion no encontrada"));
        if(!operation.getStoreId().equals(organization.currentStore().getId())) throw problem(HttpStatus.NOT_FOUND,"PAYMENT_OPERATION_NOT_FOUND","Operacion no encontrada"); return operation; }

    public PaymentTerminalOperation query(UUID id) {
        var operation=get(id);
        if (operation.getStatus()==PaymentTerminalOperationStatus.PENDING || operation.getStatus()==PaymentTerminalOperationStatus.SENT
                || operation.getStatus()==PaymentTerminalOperationStatus.TIMEOUT) return recovery.recover(id,UUID.randomUUID());
        return operation;
    }

    public PaymentTerminalOperation voidAuthorization(UUID originalId, UUID operationId, String idempotencyKey) {
        var original=get(originalId); var configuration=configuration(original);
        requireCapability(configuration,PaymentTerminalCapability.VOID);
        var hash=hash("VOID|"+originalId);
        var adjustment=adjustments.reserveVoid(operationId,originalId,configuration.terminalId(),configuration.storeId(),
                configuration.provider(),requiredKey(idempotencyKey),hash,configuration.configurationHash(),configuration.configurationVersion(),clock.instant());
        if(adjustment.getStatus()!=PaymentTerminalOperationStatus.PENDING) return adjustment;
        adjustments.markSent(operationId,clock.instant());
        PaymentTerminalResult result;
        try { result=gateway(configuration).voidAuthorization(new PaymentTerminalVoidCommand(operationId,originalId,reference(original)),context(configuration,idempotencyKey)); }
        catch(RuntimeException ex){ result=new PaymentTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,"PAYMENT_TRANSPORT_TIMEOUT",null,null,"Resultado incierto; consulte el estado"); }
        return adjustments.complete(operationId,result,clock.instant());
    }

    public PaymentTerminalOperation refund(UUID originalId, UUID operationId, String idempotencyKey, BigDecimal amount,
            Authentication authentication) {
        var original=get(originalId); var configuration=configuration(original);
        requireCapability(configuration,PaymentTerminalCapability.REFUND);
        var hash=hash("REFUND|"+originalId+"|"+amount.stripTrailingZeros().toPlainString());
        var adjustment=adjustments.reserveRefund(operationId,originalId,configuration.terminalId(),configuration.storeId(),
                configuration.provider(),requiredKey(idempotencyKey),hash,amount,configuration.configurationHash(),configuration.configurationVersion(),clock.instant());
        if(adjustment.getStatus()!=PaymentTerminalOperationStatus.PENDING) return adjustment;
        adjustments.markSent(operationId,clock.instant());
        PaymentTerminalResult result;
        try { result=gateway(configuration).refund(new PaymentTerminalRefundCommand(operationId,originalId,amount,reference(original)),context(configuration,idempotencyKey)); }
        catch(RuntimeException ex){ result=new PaymentTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,"PAYMENT_TRANSPORT_TIMEOUT",null,null,"Resultado incierto; consulte el estado"); }
        var completed=adjustments.complete(operationId,result,clock.instant());
        if(completed.getStatus()==PaymentTerminalOperationStatus.APPROVED && completed.getDocumentId()==null){
            var refreshed=get(originalId);
            if(amount.compareTo(refreshed.getAmount())!=0 || refreshed.getDocumentId()==null){
                return recovery.documentReview(operationId,"La devolucion parcial requiere desglose fiscal explicito de lineas");
            }
            try { var document=documents.createApprovedCardRefund(operationId,refreshed.getDocumentId(),amount,authentication);
                recovery.linkDocument(operationId,document.getId(),null);return get(operationId); }
            catch(RuntimeException failure){return recovery.documentFailure(operationId,"Fallo al crear el documento fiscal de devolucion");}
        }
        return completed;
    }

    public PaymentTerminalReceipt receipt(UUID id){ var op=get(id); var c=configuration(op); requireCapability(c,PaymentTerminalCapability.RECEIPT);
        var stored=receipts.findByOperationId(id);if(stored.isPresent())return stored.orElseThrow().toReceipt();
        PaymentTerminalReceipt result;
        try { result=gateway(c).receipt(new PaymentTerminalReceiptCommand(id,reference(op)),context(c,id.toString())); }
        catch(RuntimeException ex){ throw problem(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_TERMINAL_TRANSPORT_ERROR","No se pudo recuperar el recibo"); }
        var record=PaymentTerminalReceiptRecord.create(UUID.randomUUID(),id,result,clock.instant());
        receipts.save(record);return record.toReceipt(); }

    @Transactional(readOnly=true)
    public List<PaymentTerminalEvent> events(UUID id){ return List.copyOf(get(id).getEvents()); }

    public PaymentTerminalResult reconcile(UUID terminalId, UUID reconciliationId){ var c=configurations.required(terminalId);
        if(!c.storeId().equals(organization.currentStore().getId())) throw problem(HttpStatus.NOT_FOUND,"PAYMENT_TERMINAL_NOT_FOUND","Terminal no encontrado"); requireCapability(c,PaymentTerminalCapability.RECONCILIATION);
        var date=LocalDate.now(clock);var from=date.atStartOfDay().toInstant(ZoneOffset.UTC);var until=date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        var erp=operations.reconciliationTotal(c.terminalId(),c.storeId(),c.provider().name(),from,until);
        var reservation=reconciliations.reserveForSend(reconciliationId,organization.currentCompany().getId(),c,date,erp,clock.instant());
        var batch=reservation.batch();
        if(!reservation.send())return batch.result();
        PaymentTerminalResult result;
        try { result=gateway(c).reconcile(new PaymentTerminalReconciliationCommand(reconciliationId),context(c,reconciliationId.toString())); }
        catch(RuntimeException ex){ result=new PaymentTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,"PAYMENT_TERMINAL_TRANSPORT_ERROR",null,null,"Resultado incierto; vuelva a consultar o reintente la conciliacion"); }
        var provider=c.testMode()?erp:BigDecimal.ZERO;
        return reconciliations.complete(reconciliationId,provider,result,clock.instant()).result(); }

    public PaymentTerminalReconciliationBatch reconciliation(UUID id){var store=organization.currentStore();
        return reconciliations.required(id,store.getId(),store.getEmpresa().getId());}

    private CardTerminalConfiguration configuration(PaymentTerminalOperation op){ var c=configurations.required(op.getTerminalId());
        if(c.provider()!=op.getProvider() || c.configurationVersion()!=op.getConfigurationVersion()
                || !java.util.Objects.equals(c.configurationHash(),op.getConfigurationHash()))
            throw problem(HttpStatus.CONFLICT,"PAYMENT_CONFIGURATION_CHANGED","La configuracion del datafono ha cambiado"); return c; }
    private CardTerminalGateway gateway(CardTerminalConfiguration c){ return gateways.stream().filter(g->g.supports(c.provider(),c.testMode())).findFirst()
            .orElseThrow(()->problem(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_GATEWAY_UNAVAILABLE","Conector no disponible")); }
    private void requireCapability(CardTerminalConfiguration c,PaymentTerminalCapability capability){ if(!gateway(c).capabilities().contains(capability))
        throw problem(HttpStatus.UNPROCESSABLE_CONTENT,"PAYMENT_CAPABILITY_UNAVAILABLE","Operacion no admitida por el proveedor"); }
    private PaymentTerminalGatewayContext context(CardTerminalConfiguration c,String key){ return new PaymentTerminalGatewayContext(c.terminalId(),c.provider(),
            c.testMode()?PaymentTerminalMode.SIMULATED:PaymentTerminalMode.LIVE,"EUR",key,c.configurationReference(),c.configurationVersion(),c.configurationHash(),c.parameters()); }
    private static String reference(PaymentTerminalOperation op){ return op.getExternalReference()==null?op.getId().toString():op.getExternalReference(); }
    private static String requiredKey(String key){ if(key==null||key.isBlank()||key.length()>128) throw problem(HttpStatus.BAD_REQUEST,"PAYMENT_IDEMPOTENCY_KEY_INVALID","Clave idempotente invalida"); return key.trim(); }
    private static String hash(String value){ try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){ throw new IllegalStateException(e); } }
    private static PaymentTerminalApiException problem(HttpStatus status,String code,String message){ return new PaymentTerminalApiException(status,code,message); }
}
