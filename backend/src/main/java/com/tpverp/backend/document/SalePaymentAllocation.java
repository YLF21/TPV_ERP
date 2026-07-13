package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="sale_payment_allocation",uniqueConstraints=@UniqueConstraint(columnNames={"session_id","idempotency_key"}))
public class SalePaymentAllocation {
 @Id private UUID id; @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="session_id") private SalePaymentSession session;
 @Column(name="idempotency_key",nullable=false,length=128) private String idempotencyKey;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private SalePaymentAllocationKind kind;
 @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
 @Column(length=32) private String provider; @Column(length=16) private String mode; @Column(name="operation_id") private UUID operationId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private PaymentTerminalOperationStatus status;
 @Column(length=128) private String reference; @Column(length=64) private String authorization; @Column(length=512) private String message;
 @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt; @Version private long version;
 protected SalePaymentAllocation(){}
 static SalePaymentAllocation create(SalePaymentSession session,UUID id,String key,SalePaymentAllocationKind kind,BigDecimal amount,String provider,String mode){var a=new SalePaymentAllocation();a.session=session;a.id=id;a.idempotencyKey=key.trim();a.kind=kind;a.amount=Money.euros(amount);a.provider=provider;a.mode=mode;a.status=PaymentTerminalOperationStatus.PENDING;a.createdAt=Instant.now();a.updatedAt=a.createdAt;return a;}
 public void approve(UUID operationId,String reference,String authorization){this.operationId=operationId;this.reference=safe(reference);this.authorization=safe(authorization);this.status=PaymentTerminalOperationStatus.APPROVED;this.updatedAt=Instant.now();session.refreshCoverage();}
 public void result(PaymentTerminalOperationStatus status,UUID operationId,String reference,String authorization,String message){this.status=status;this.operationId=operationId;this.reference=safe(reference);this.authorization=safe(authorization);this.message=safe(message);this.updatedAt=Instant.now();session.refreshCoverage();}
 private static String safe(String s){return s==null||s.isBlank()?null:s.trim();}
 public UUID getId(){return id;} public String getIdempotencyKey(){return idempotencyKey;} public SalePaymentAllocationKind getKind(){return kind;} public BigDecimal getAmount(){return amount;} public String getProvider(){return provider;} public String getMode(){return mode;} public UUID getOperationId(){return operationId;} public PaymentTerminalOperationStatus getStatus(){return status;} public String getReference(){return reference;} public String getAuthorization(){return authorization;} public String getMessage(){return message;}
}
