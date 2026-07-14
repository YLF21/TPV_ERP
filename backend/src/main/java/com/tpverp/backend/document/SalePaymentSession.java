package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="sale_payment_session")
public class SalePaymentSession {
 @Id private UUID id; @Column(name="store_id",nullable=false) private UUID storeId; @Column(name="terminal_id",nullable=false) private UUID terminalId; @Column(name="user_id",nullable=false) private UUID userId;
 @Column(name="request_hash",nullable=false,length=64) private String requestHash; @JdbcTypeCode(SqlTypes.JSON) @Column(name="document_snapshot",nullable=false,columnDefinition="jsonb") private String snapshot;
 @Column(nullable=false,precision=19,scale=2) private BigDecimal total; @Column(nullable=false,length=3) private String currency="EUR";
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private SalePaymentSessionStatus status; @Column(name="ticket_id") private UUID ticketId; @Column(name="ticket_number",length=32) private String ticketNumber;
 @Column(name="compensation_note",length=512) private String compensationNote; @Column(name="compensation_resolved_by") private UUID compensationResolvedBy; @Column(name="compensation_resolved_at") private Instant compensationResolvedAt;
 @OneToMany(mappedBy="session",cascade=CascadeType.ALL,orphanRemoval=true) @OrderBy("createdAt asc") private List<SalePaymentAllocation> allocations=new ArrayList<>();
 @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt; @Version private long version;
 protected SalePaymentSession(){}
 public static SalePaymentSession reserve(UUID id,UUID storeId,UUID terminalId,UUID userId,String hash,String snapshot,BigDecimal total){var s=new SalePaymentSession();s.id=id;s.storeId=storeId;s.terminalId=terminalId;s.userId=userId;s.requestHash=hash;s.snapshot=snapshot;s.total=Money.euros(total);s.status=SalePaymentSessionStatus.COLLECTING;s.createdAt=Instant.now();s.updatedAt=s.createdAt;return s;}
 public SalePaymentAllocation addAllocation(UUID id,String key,SalePaymentAllocationKind kind,BigDecimal amount,String provider,String mode){if(status!=SalePaymentSessionStatus.COLLECTING)throw new IllegalStateException("payment_session_not_collecting");if(allocations.stream().anyMatch(a->a.getIdempotencyKey().equals(key)))throw new IllegalStateException("duplicate_idempotency_key");var normalized=Money.euros(amount);var reserved=allocations.stream().filter(a->a.getStatus()!=PaymentTerminalOperationStatus.DECLINED&&a.getStatus()!=PaymentTerminalOperationStatus.ERROR&&a.getStatus()!=PaymentTerminalOperationStatus.CANCELLED).map(SalePaymentAllocation::getAmount).reduce(Money.euros(BigDecimal.ZERO),BigDecimal::add);if(normalized.signum()<=0||reserved.add(normalized).compareTo(total)>0)throw new IllegalArgumentException("allocation_exceeds_remaining");var a=SalePaymentAllocation.create(this,id,key,kind,normalized,provider,mode);allocations.add(a);updatedAt=Instant.now();return a;}
 void refreshCoverage(){status=approvedTotal().compareTo(total)==0?SalePaymentSessionStatus.COVERED:SalePaymentSessionStatus.COLLECTING;updatedAt=Instant.now();}
 public BigDecimal approvedTotal(){return allocations.stream().filter(a->a.getStatus()==PaymentTerminalOperationStatus.APPROVED).map(SalePaymentAllocation::getAmount).reduce(Money.euros(BigDecimal.ZERO),BigDecimal::add);}
 public boolean isCovered(){return approvedTotal().compareTo(total)==0;}
 public void finalizeWith(UUID ticketId,String number){if(this.ticketId!=null)return;if(status!=SalePaymentSessionStatus.COVERED)throw new IllegalStateException("payment_session_not_finalizable");if(!isCovered())throw new IllegalStateException("payment_session_not_covered");this.ticketId=ticketId;this.ticketNumber=number;status=SalePaymentSessionStatus.FINALIZED;updatedAt=Instant.now();}
 public void cancel(){if(status==SalePaymentSessionStatus.FINALIZED||status==SalePaymentSessionStatus.CANCELLED||status==SalePaymentSessionStatus.COMPENSATION_REQUIRED)return;var uncertainIntegrated=allocations.stream().anyMatch(a->a.requiresCompensationOnCancel());if(approvedTotal().signum()>0||uncertainIntegrated)status=SalePaymentSessionStatus.COMPENSATION_REQUIRED;else status=SalePaymentSessionStatus.CANCELLED;updatedAt=Instant.now();}
 public void acknowledgeCompensation(String note,UUID userId){if(status!=SalePaymentSessionStatus.COMPENSATION_REQUIRED)throw new IllegalStateException("compensation_not_required");if(note==null||note.isBlank())throw new IllegalArgumentException("compensation_note_required");compensationNote=note.trim();compensationResolvedBy=Objects.requireNonNull(userId);compensationResolvedAt=Instant.now();status=SalePaymentSessionStatus.CANCELLED;updatedAt=compensationResolvedAt;}
 public void discardSimulation(String reason,UUID userId){var normalizedReason=SimulatorDiscardReason.require(reason);var resolvedBy=Objects.requireNonNull(userId);if(status==SalePaymentSessionStatus.FINALIZED)throw new IllegalStateException("payment_session_finalized");compensationNote=normalizedReason;compensationResolvedBy=resolvedBy;compensationResolvedAt=Instant.now();status=SalePaymentSessionStatus.CANCELLED;updatedAt=compensationResolvedAt;}
 public UUID getId(){return id;} public UUID getStoreId(){return storeId;} public UUID getTerminalId(){return terminalId;} public UUID getUserId(){return userId;} public String getRequestHash(){return requestHash;} public String getSnapshot(){return snapshot;} public BigDecimal getTotal(){return total;} public String getCurrency(){return currency;} public SalePaymentSessionStatus getStatus(){return status;} public UUID getTicketId(){return ticketId;} public String getTicketNumber(){return ticketNumber;} public List<SalePaymentAllocation> getAllocations(){return List.copyOf(allocations);}
 public String getCompensationNote(){return compensationNote;} public UUID getCompensationResolvedBy(){return compensationResolvedBy;} public Instant getCompensationResolvedAt(){return compensationResolvedAt;}
}
