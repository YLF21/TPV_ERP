package com.tpverp.backend.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/pos/payment-sessions")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
public class SalePaymentSessionController {
 private final SalePaymentSessionService service; public SalePaymentSessionController(SalePaymentSessionService service){this.service=service;}
 @PostMapping public View reserve(@Valid @RequestBody Reserve request,Authentication auth){return View.from(service.reserve(request.sessionId(),request.sale(),auth));}
 @GetMapping("/{id}") public View get(@PathVariable UUID id,Authentication auth){return View.from(service.get(id,auth));}
 @GetMapping("/active") public ResponseEntity<View> active(Authentication auth){return service.active(auth).map(session->ResponseEntity.ok(View.from(session))).orElseGet(()->ResponseEntity.noContent().build());}
 @PostMapping("/{id}/allocations") public View add(@PathVariable UUID id,@Valid @RequestBody Allocation request,Authentication auth){return View.from(service.add(id,request.allocationId(),request.idempotencyKey(),request.kind(),request.amount(),request.provider(),request.voucherCode(),request.reference(),request.delivered(),request.change(),request.comment(),request.operationAuthorization(),auth));}
 @PostMapping("/{id}/allocations/{allocationId}/query") public View query(@PathVariable UUID id,@PathVariable UUID allocationId,Authentication auth){return View.from(service.query(id,allocationId,auth));}
 @PostMapping("/{id}/finalize") public View finalizeSession(@PathVariable UUID id,@Valid @RequestBody(required=false) FinalizeRequest request,Authentication auth){var effective=request==null?new FinalizeRequest(null,null,null):request;var override=effective.creditOverride();var finalized=service.finalizeSession(id,override==null?null:override.reason(),effective.authorizerUsername(),effective.authorizerPassword(),override==null?null:override.authorizerUsername(),override==null?null:override.authorizerPassword(),auth);return View.from(finalized.session(),finalized.printTicket());}
 View finalizeSession(UUID id,Authentication auth){var finalized=service.finalizeSession(id,auth);return View.from(finalized.session(),finalized.printTicket());}
 @PostMapping("/{id}/cancel") public View cancel(@PathVariable UUID id,Authentication auth){return View.from(service.cancel(id,auth));}
 @PostMapping("/{id}/simulator-discard") public View discardSimulation(@PathVariable UUID id,@Valid @RequestBody SimulatorDiscard request,Authentication auth){return View.from(service.discardSimulation(id,request.reason(),auth));}
 @PostMapping("/{id}/compensation-ack") @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE','PAYMENT_TERMINAL_REFUND')") public View acknowledge(@PathVariable UUID id,@Valid @RequestBody CompensationAck request,Authentication auth){return View.from(service.acknowledgeCompensation(id,request.note(),request.authorizerUsername(),request.authorizerPassword(),auth));}
 public record Reserve(@NotNull UUID sessionId,@NotNull @Valid PosCashController.SaleRequest sale){}
 public record Allocation(@NotNull UUID allocationId,@NotBlank String idempotencyKey,@NotNull SalePaymentAllocationKind kind,@NotNull @DecimalMin("0.01") BigDecimal amount,String provider,@Size(max=128) String voucherCode,@Size(max=128) String reference,@DecimalMin("0.00") BigDecimal delivered,@DecimalMin("0.00") BigDecimal change,@Size(max=512) String comment,@Valid OperationAuthorizationRequest operationAuthorization){}
 public record FinalizeRequest(
         @Valid CreditOverride creditOverride,
         @Size(max=128) String authorizerUsername,
         @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
         @Size(max=128) String authorizerPassword) {
  @Override public String toString() {
   return "FinalizeRequest[creditOverride=" + creditOverride
           + ", authorizerUsername=" + authorizerUsername
           + ", authorizerPassword=<redacted>]";
  }
 }
 public record CreditOverride(
         @NotBlank @Size(max=500) String reason,
         @Size(max=128) String authorizerUsername,
         @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
         @Size(max=128) String authorizerPassword) {
  public CreditOverride(String reason){this(reason,null,null);}
  @Override public String toString() {
   return "CreditOverride[reason=" + reason
           + ", authorizerUsername=" + authorizerUsername
           + ", authorizerPassword=<redacted>]";
  }
 }
 public record CompensationAck(
         @NotBlank @Size(max=512) String note,
         @Size(max=128) String authorizerUsername,
         @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
         @Size(max=128) String authorizerPassword) {
  @Override public String toString() {
   return "CompensationAck[note=" + note
           + ", authorizerUsername=" + authorizerUsername
           + ", authorizerPassword=<redacted>]";
  }
 }
 public record SimulatorDiscard(@NotBlank String reason){@AssertTrue boolean isSupportedReason(){return SimulatorDiscardReason.isAllowed(reason);}}
 public record AllocationView(UUID id,String idempotencyKey,SalePaymentAllocationKind kind,BigDecimal amount,BigDecimal delivered,BigDecimal change,String comment,String provider,String mode,UUID operationId,String status,String lifecycleStatus,String voucherCode,String reference,String authorization,String message){static AllocationView from(SalePaymentAllocation a){return new AllocationView(a.getId(),a.getIdempotencyKey(),a.getKind(),a.getAmount(),a.getDelivered(),a.getChange(),a.getComment(),a.getProvider(),a.getMode(),a.getOperationId(),a.getStatus().name(),com.tpverp.backend.terminal.PaymentLifecycleStatus.from(a.getStatus()).name(),a.getVoucherCode(),a.getReference(),a.getAuthorization(),a.getMessage());}}
 public record View(UUID id,BigDecimal total,String currency,String status,UUID ticketId,String ticketNumber,List<AllocationView> allocations,TicketPrintView printTicket){static View from(SalePaymentSession s){return from(s,null);}static View from(SalePaymentSession s,TicketPrintView printTicket){return new View(s.getId(),s.getTotal(),s.getCurrency(),s.getStatus().name(),s.getTicketId(),s.getTicketNumber(),s.getAllocations().stream().map(AllocationView::from).toList(),printTicket);}}
}
