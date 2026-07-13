package com.tpverp.backend.terminal;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="payment_terminal_reconciliation_event")
public class PaymentTerminalReconciliationEvent {@Id private UUID id; @Column(name="reconciliation_id",nullable=false) private UUID reconciliationId;
 @Column(nullable=false,length=32) private String status; @Column(name="normalized_code",nullable=false,length=64) private String normalizedCode;
 @Column(length=512) private String diagnostic; @Column(name="created_at",nullable=false) private Instant createdAt; protected PaymentTerminalReconciliationEvent(){}
 public static PaymentTerminalReconciliationEvent from(UUID reconciliationId,PaymentTerminalResult result,Instant at){var e=new PaymentTerminalReconciliationEvent();e.id=UUID.randomUUID();e.reconciliationId=reconciliationId;e.status=result.status().name();e.normalizedCode=result.code();e.diagnostic=PaymentTerminalSensitiveData.mask(result.message());e.createdAt=at;return e;}
 public String getDiagnostic(){return diagnostic;}}
