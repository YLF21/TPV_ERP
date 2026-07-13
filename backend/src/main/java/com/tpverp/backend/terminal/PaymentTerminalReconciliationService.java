package com.tpverp.backend.terminal;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTerminalReconciliationService {
    private final PaymentTerminalReconciliationBatchRepository batches;
    private final PaymentTerminalReconciliationEventRepository events;
    @SuppressWarnings("unused") private final CurrentOrganization organization;
    public PaymentTerminalReconciliationService(PaymentTerminalReconciliationBatchRepository batches,
            PaymentTerminalReconciliationEventRepository events,CurrentOrganization organization){
        this.batches=batches;this.events=events;this.organization=organization;
    }

    public PaymentTerminalReconciliationBatch reserve(UUID id,UUID companyId,CardTerminalConfiguration configuration,
            LocalDate date,BigDecimal erpTotal,Instant now){
        return reserveForSend(id,companyId,configuration,date,erpTotal,now).batch();
    }

    public Reservation reserveForSend(UUID id,UUID companyId,CardTerminalConfiguration configuration,
            LocalDate date,BigDecimal erpTotal,Instant now){
        var existing=batches.findByIdAndStoreIdAndCompanyId(id,configuration.storeId(),companyId);
        if(existing.isPresent())return new Reservation(existing.orElseThrow(),false);
        var batch=PaymentTerminalReconciliationBatch.reserve(id,companyId,configuration,date,erpTotal,now);
        try{return new Reservation(batches.saveAndFlush(batch),true);}catch(DataIntegrityViolationException duplicate){
            return new Reservation(batches.findByIdAndStoreIdAndCompanyId(id,configuration.storeId(),companyId).orElseThrow(()->duplicate),false);
        }
    }

    @Transactional
    public PaymentTerminalReconciliationBatch complete(UUID id,BigDecimal providerTotal,PaymentTerminalResult result,Instant now){
        var store=organization.currentStore();
        var batch=required(id,store.getId(),store.getEmpresa().getId());
        if(PaymentTerminalOperationStatus.PENDING.name().equals(batch.getStatus())){
            batch.complete(providerTotal,result,now);events.save(PaymentTerminalReconciliationEvent.from(id,result,now));
        }
        return batch;
    }

    @Transactional(readOnly=true)
    public PaymentTerminalReconciliationBatch required(UUID id,UUID storeId,UUID companyId){
        return batches.findByIdAndStoreIdAndCompanyId(id,storeId,companyId).orElseThrow(()->new PaymentTerminalApiException(
                HttpStatus.NOT_FOUND,"PAYMENT_RECONCILIATION_NOT_FOUND","Conciliacion no encontrada"));
    }
    public record Reservation(PaymentTerminalReconciliationBatch batch,boolean send){}
}
