package com.tpverp.backend.document;

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
 @PostMapping("/{id}/allocations") public View add(@PathVariable UUID id,@Valid @RequestBody Allocation request,Authentication auth){return View.from(service.add(id,request.allocationId(),request.idempotencyKey(),request.kind(),request.amount(),request.provider(),request.reference(),auth));}
 @PostMapping("/{id}/allocations/{allocationId}/query") public View query(@PathVariable UUID id,@PathVariable UUID allocationId,Authentication auth){return View.from(service.query(id,allocationId,auth));}
 @PostMapping("/{id}/finalize") public View finalizeSession(@PathVariable UUID id,Authentication auth){var finalized=service.finalizeSession(id,auth);return View.from(finalized.session(),finalized.printTicket());}
 @PostMapping("/{id}/cancel") public View cancel(@PathVariable UUID id,Authentication auth){return View.from(service.cancel(id,auth));}
 @PostMapping("/{id}/simulator-discard") public View discardSimulation(@PathVariable UUID id,@Valid @RequestBody SimulatorDiscard request,Authentication auth){return View.from(service.discardSimulation(id,request.reason(),auth));}
 @PostMapping("/{id}/compensation-ack") @PreAuthorize("hasRole('ADMIN') or hasAuthority('PAYMENT_TERMINAL_REFUND')") public View acknowledge(@PathVariable UUID id,@Valid @RequestBody CompensationAck request,Authentication auth){return View.from(service.acknowledgeCompensation(id,request.note(),auth));}
 public record Reserve(@NotNull UUID sessionId,@NotNull @Valid PosCashController.SaleRequest sale){}
 public record Allocation(@NotNull UUID allocationId,@NotBlank String idempotencyKey,@NotNull SalePaymentAllocationKind kind,@NotNull @DecimalMin("0.01") BigDecimal amount,String provider,String reference){}
 public record CompensationAck(@NotBlank @Size(max=512) String note){}
 public record SimulatorDiscard(@NotBlank String reason){@AssertTrue boolean isSupportedReason(){return SimulatorDiscardReason.isAllowed(reason);}}
 public record AllocationView(UUID id,String idempotencyKey,SalePaymentAllocationKind kind,BigDecimal amount,String provider,String mode,UUID operationId,String status,String lifecycleStatus,String reference,String authorization,String message){static AllocationView from(SalePaymentAllocation a){return new AllocationView(a.getId(),a.getIdempotencyKey(),a.getKind(),a.getAmount(),a.getProvider(),a.getMode(),a.getOperationId(),a.getStatus().name(),com.tpverp.backend.terminal.PaymentLifecycleStatus.from(a.getStatus()).name(),a.getReference(),a.getAuthorization(),a.getMessage());}}
 public record View(UUID id,BigDecimal total,String currency,String status,UUID ticketId,String ticketNumber,List<AllocationView> allocations,TicketPrintView printTicket){static View from(SalePaymentSession s){return from(s,null);}static View from(SalePaymentSession s,TicketPrintView printTicket){return new View(s.getId(),s.getTotal(),s.getCurrency(),s.getStatus().name(),s.getTicketId(),s.getTicketNumber(),s.getAllocations().stream().map(AllocationView::from).toList(),printTicket);}}
}
