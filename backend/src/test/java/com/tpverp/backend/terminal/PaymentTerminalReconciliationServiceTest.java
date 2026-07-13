package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalReconciliationServiceTest {
    private final PaymentTerminalReconciliationBatchRepository batches = mock(PaymentTerminalReconciliationBatchRepository.class);
    private final PaymentTerminalReconciliationEventRepository events = mock(PaymentTerminalReconciliationEventRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final PaymentTerminalReconciliationService service = new PaymentTerminalReconciliationService(batches, events, organization);

    @Test
    void reservesPendingBatchBeforeItCanBeCompleted() {
        var id=UUID.randomUUID(); var companyId=UUID.randomUUID(); var configuration=configuration();
        when(batches.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var batch=service.reserve(id,companyId,configuration,LocalDate.of(2026,7,13),new BigDecimal("12.30"),Instant.parse("2026-07-13T10:00:00Z"));

        assertThat(batch.getStatus()).isEqualTo(PaymentTerminalOperationStatus.PENDING.name());
        assertThat(batch.getCompanyId()).isEqualTo(companyId);
        verify(batches).saveAndFlush(batch);
    }

    @Test
    void replayReturnsExistingFinalBatchWithoutReplacingIt() {
        var id=UUID.randomUUID();var configuration=configuration(); var existing=PaymentTerminalReconciliationBatch.reserve(id,UUID.randomUUID(),configuration,
                LocalDate.of(2026,7,13),BigDecimal.TEN,Instant.parse("2026-07-13T10:00:00Z"));
        existing.complete(BigDecimal.TEN,new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"RECONCILED","ref",null,"ok"),Instant.parse("2026-07-13T10:01:00Z"));
        when(batches.findByIdAndStoreIdAndCompanyId(id,existing.getStoreId(),existing.getCompanyId())).thenReturn(Optional.of(existing));

        assertThat(service.reserve(id,existing.getCompanyId(),configuration,LocalDate.now(),BigDecimal.ZERO,Instant.now())).isSameAs(existing);
    }

    @Test
    void lookupRequiresBothCurrentStoreAndCompany() {
        var id=UUID.randomUUID(); var storeId=UUID.randomUUID(); var companyId=UUID.randomUUID();
        when(batches.findByIdAndStoreIdAndCompanyId(id,storeId,companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.required(id,storeId,companyId))
                .isInstanceOf(PaymentTerminalApiException.class);
    }

    @Test
    void completionCannotLoadAReconciliationOutsideCurrentStoreAndCompany() {
        var id=UUID.randomUUID(); var storeId=UUID.randomUUID(); var companyId=UUID.randomUUID();
        var store=mock(com.tpverp.backend.organization.Store.class);
        var company=mock(com.tpverp.backend.organization.Company.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(batches.findByIdAndStoreIdAndCompanyId(id,storeId,companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(id,BigDecimal.TEN,
                new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"OK",null,null,"ok"),Instant.now()))
                .isInstanceOf(PaymentTerminalApiException.class);

        verify(batches,never()).findById(id);
        verify(events,never()).save(any());
    }

    private static CardTerminalConfiguration configuration(){return new CardTerminalConfiguration(UUID.randomUUID(),UUID.randomUUID(),
            PaymentCardMode.INTEGRATED,PaymentTerminalProvider.REDSYS_TPV_PC,true,true,"Terminal","ref",1,"hash",Map.of());}
}
