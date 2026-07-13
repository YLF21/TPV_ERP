package com.tpverp.backend.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payment_terminal_reconciliation_batch")
public class PaymentTerminalReconciliationBatch {
    @Id private UUID id;
    @Column(name="terminal_id",nullable=false) private UUID terminalId;
    @Column(name="store_id",nullable=false) private UUID storeId;
    @Column(name="company_id",nullable=false) private UUID companyId;
    @Column(nullable=false,length=32) private String provider;
    @Column(name="business_date",nullable=false) private LocalDate businessDate;
    @Column(nullable=false,length=32) private String status;
    @Column(name="erp_total",nullable=false,precision=19,scale=2) private BigDecimal erpTotal;
    @Column(name="provider_total",nullable=false,precision=19,scale=2) private BigDecimal providerTotal;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal discrepancy;
    @Column(name="normalized_code",nullable=false,length=64) private String normalizedCode;
    @Column(name="external_reference",length=128) private String externalReference;
    @Column(length=512) private String diagnostic;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected PaymentTerminalReconciliationBatch() {}
    public static PaymentTerminalReconciliationBatch reserve(UUID id,UUID companyId,CardTerminalConfiguration c,LocalDate date,
            BigDecimal erpTotal,Instant now){var b=new PaymentTerminalReconciliationBatch();b.id=id;b.terminalId=c.terminalId();
        b.storeId=c.storeId();b.companyId=companyId;b.provider=c.provider().name();b.businessDate=date;
        b.status=PaymentTerminalOperationStatus.PENDING.name();b.erpTotal=money(erpTotal);b.providerTotal=money(BigDecimal.ZERO);
        b.discrepancy=b.providerTotal.subtract(b.erpTotal);b.normalizedCode="RECONCILIATION_RESERVED";
        b.diagnostic="Conciliacion reservada";b.createdAt=now;b.updatedAt=now;return b;}
    public void complete(BigDecimal providerTotal,PaymentTerminalResult result,Instant now){
        if(!PaymentTerminalOperationStatus.PENDING.name().equals(status))return;
        this.status=result.status().name();this.providerTotal=money(providerTotal);this.discrepancy=this.providerTotal.subtract(erpTotal);
        this.normalizedCode=result.code();this.externalReference=PaymentTerminalSensitiveData.mask(result.reference());
        this.diagnostic=PaymentTerminalSensitiveData.mask(result.message());this.updatedAt=now;
    }
    private static BigDecimal money(BigDecimal value){return value.setScale(2,java.math.RoundingMode.HALF_UP);}
    public BigDecimal getErpTotal(){return erpTotal;} public BigDecimal getProviderTotal(){return providerTotal;}
    public BigDecimal getDiscrepancy(){return discrepancy;} public String getStatus(){return status;}
    public UUID getId(){return id;} public UUID getStoreId(){return storeId;} public UUID getCompanyId(){return companyId;}
    public UUID getTerminalId(){return terminalId;} public String getProvider(){return provider;} public LocalDate getBusinessDate(){return businessDate;}
    public String getNormalizedCode(){return normalizedCode;} public String getExternalReference(){return externalReference;}
    public String getDiagnostic(){return diagnostic;}
    public PaymentTerminalResult result(){return new PaymentTerminalResult(PaymentTerminalOperationStatus.valueOf(status),normalizedCode,externalReference,null,diagnostic);}
}
