package com.tpverp.backend.terminal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PAYMENT_TERMINAL_REFUND;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PAYMENT_TERMINAL_VOID;

@RestController
@RequestMapping("/api/v1/payment-terminal")
public class PaymentTerminalOperationController {
    private final PaymentTerminalOperationsService service;
    private final PaymentTerminalReauthenticationService reauthentication;
    public PaymentTerminalOperationController(PaymentTerminalOperationsService service,
            PaymentTerminalReauthenticationService reauthentication){
        this.service=service;
        this.reauthentication=reauthentication;
    }

    @GetMapping("/operations/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS','TICKETS_READ')")
    public OperationView status(@PathVariable UUID id){ return OperationView.from(service.get(id)); }

    @GetMapping("/operations/by-payment/{paymentId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS','TICKETS_READ')")
    public OperationView byPayment(@PathVariable UUID paymentId){
        return service.findByDocumentPaymentId(paymentId)
                .map(OperationView::from)
                .orElseThrow(() -> new IllegalArgumentException("Operacion de tarjeta no encontrada"));
    }

    @PostMapping("/operations/{id}/query")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS','TICKETS_READ')")
    public OperationView query(@PathVariable UUID id){ return OperationView.from(service.query(id)); }

    @PostMapping("/operations/{id}/void")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PAYMENT_TERMINAL_VOID + "')")
    public OperationView voidAuthorization(@PathVariable UUID id,@Valid @RequestBody AdjustmentRequest request,
            Authentication authentication){
        reauthentication.require(authentication, request.password());
        return OperationView.from(service.voidAuthorization(id,request.operationId(),request.idempotencyKey())); }

    @PostMapping("/operations/{id}/refund")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PAYMENT_TERMINAL_REFUND + "')")
    public OperationView refund(@PathVariable UUID id,@Valid @RequestBody RefundRequest request,Authentication authentication){
        reauthentication.require(authentication, request.password());
        var lines=request.lines()==null?List.<PaymentTerminalRefundLineSelection>of():request.lines().stream()
                .map(line->new PaymentTerminalRefundLineSelection(line.lineId(),line.quantity())).toList();
        return OperationView.from(service.refund(id,request.operationId(),request.idempotencyKey(),request.amount(),lines,authentication)); }

    @GetMapping("/operations/{id}/refund-lines")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PAYMENT_TERMINAL_REFUND + "')")
    public List<com.tpverp.backend.document.DocumentService.CardRefundLineOption> refundLines(@PathVariable UUID id){
        return service.refundLineOptions(id); }

    @GetMapping("/operations/{id}/receipt")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS','TICKETS_READ')")
    public ReceiptView receipt(@PathVariable UUID id){ var receipt=service.receipt(id); return new ReceiptView(receipt.status(),receipt.code(),receipt.text()); }

    @GetMapping("/operations/{id}/events")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS','TICKETS_READ')")
    public List<EventView> events(@PathVariable UUID id){ return service.events(id).stream().map(EventView::from).toList(); }

    @PostMapping("/terminals/{terminalId}/reconciliations")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('PAYMENT_TERMINAL_RECONCILE','CONFIGURACION_TERMINAL')")
    public ResultView reconcile(@PathVariable UUID terminalId,@Valid @RequestBody ReconciliationRequest request){
        return ResultView.from(service.reconcile(terminalId,request.reconciliationId())); }

    @GetMapping("/reconciliations/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('PAYMENT_TERMINAL_RECONCILE','CONFIGURACION_TERMINAL')")
    public ReconciliationView reconciliation(@PathVariable UUID id){return ReconciliationView.from(service.reconciliation(id));}

    public record AdjustmentRequest(@NotNull UUID operationId,@NotBlank String idempotencyKey,String password) {}
    public record RefundRequest(@NotNull UUID operationId,@NotBlank String idempotencyKey,
            String password,@NotNull @DecimalMin(value="0.01") BigDecimal amount,
            @Valid List<RefundLineRequest> lines) {}
    public record RefundLineRequest(@NotNull UUID lineId,
            @NotNull @DecimalMin(value="0.001") BigDecimal quantity) {}
    public record ReconciliationRequest(@NotNull UUID reconciliationId) {}
    public record OperationView(UUID id,UUID terminalId,UUID storeId,PaymentTerminalProvider provider,PaymentTerminalMode mode,
            PaymentTerminalOperationType type,UUID originalOperationId,BigDecimal amount,String currency,BigDecimal refundedAmount,
            PaymentTerminalOperationStatus status,String reference,String authorization,UUID documentId) {
        static OperationView from(PaymentTerminalOperation o){ return new OperationView(o.getId(),o.getTerminalId(),o.getStoreId(),o.getProvider(),o.getMode(),
                o.getOperationType(),o.getOriginalOperationId(),o.getAmount(),o.getCurrency(),o.getRefundedAmount(),o.getStatus(),
                masked(o.getExternalReference()),masked(o.getAuthorizationCode()),o.getDocumentId()); }
        private static String masked(String value){ if(value==null||value.isBlank())return null; var tail=value.substring(Math.max(0,value.length()-4)); return "****"+tail; }
    }
    public record EventView(PaymentTerminalOperationStatus previousStatus,PaymentTerminalOperationStatus status,String code,
            String diagnostic,Map<String,Object> metadata,Instant createdAt){ static EventView from(PaymentTerminalEvent e){ return new EventView(
                    e.getPreviousStatus(),e.getNewStatus(),e.getNormalizedCode(),e.getDiagnostic(),e.getMetadata(),e.getCreatedAt()); } }
    public record ReceiptView(PaymentTerminalOperationStatus status,String code,String text) {}
    public record ResultView(PaymentTerminalOperationStatus status,String code,String reference,String authorization,String message){
        static ResultView from(PaymentTerminalResult r){ return new ResultView(r.status(),r.code(),r.reference(),r.authorization(),r.message()); } }
    public record ReconciliationView(UUID id,UUID terminalId,LocalDate businessDate,PaymentTerminalOperationStatus status,
            BigDecimal erpTotal,BigDecimal providerTotal,BigDecimal discrepancy,String code,String reference,String diagnostic){
        static ReconciliationView from(PaymentTerminalReconciliationBatch b){return new ReconciliationView(b.getId(),b.getTerminalId(),b.getBusinessDate(),
                PaymentTerminalOperationStatus.valueOf(b.getStatus()),b.getErpTotal(),b.getProviderTotal(),b.getDiscrepancy(),b.getNormalizedCode(),b.getExternalReference(),b.getDiagnostic());}}
}
