package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalOperationsReconciliationTest {
    @Test
    void persistsReservationBeforeGatewayIoAndCompletesAfterwards() {
        var fixture=new Fixture();
        var reserved=PaymentTerminalReconciliationBatch.reserve(fixture.reconciliationId,fixture.companyId,fixture.configuration,
                java.time.LocalDate.of(2026,7,13),BigDecimal.TEN,fixture.clock.instant());
        when(fixture.coordinator.reserveForSend(eq(fixture.reconciliationId),eq(fixture.companyId),eq(fixture.configuration),any(),eq(BigDecimal.TEN),any()))
                .thenReturn(new PaymentTerminalReconciliationService.Reservation(reserved,true));
        var gatewayResult=new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"RECONCILED","ref",null,"ok");
        when(fixture.gateway.reconcile(any(),any())).thenReturn(gatewayResult);
        when(fixture.coordinator.complete(any(),any(),any(),any())).thenAnswer(invocation->{
            reserved.complete(BigDecimal.TEN,gatewayResult,fixture.clock.instant());return reserved;});

        assertThat(fixture.service().reconcile(fixture.terminalId,fixture.reconciliationId).status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        var order=inOrder(fixture.coordinator,fixture.gateway);
        order.verify(fixture.coordinator).reserveForSend(eq(fixture.reconciliationId),eq(fixture.companyId),eq(fixture.configuration),any(),eq(BigDecimal.TEN),any());
        order.verify(fixture.gateway).reconcile(any(),any());
        order.verify(fixture.coordinator).complete(eq(fixture.reconciliationId),eq(BigDecimal.TEN),eq(gatewayResult),any());
    }

    @Test
    void replayOfFinalReservationDoesNotCallGatewayAgain() {
        var fixture=new Fixture();
        var finalBatch=PaymentTerminalReconciliationBatch.reserve(fixture.reconciliationId,fixture.companyId,fixture.configuration,
                java.time.LocalDate.of(2026,7,13),BigDecimal.TEN,fixture.clock.instant());
        finalBatch.complete(BigDecimal.TEN,new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"RECONCILED","ref",null,"ok"),fixture.clock.instant());
        when(fixture.coordinator.reserveForSend(any(),any(),any(),any(),any(),any()))
                .thenReturn(new PaymentTerminalReconciliationService.Reservation(finalBatch,false));

        assertThat(fixture.service().reconcile(fixture.terminalId,fixture.reconciliationId).status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        verify(fixture.gateway,never()).reconcile(any(),any());
    }

    private static final class Fixture {
        final UUID terminalId=UUID.randomUUID(),storeId=UUID.randomUUID(),companyId=UUID.randomUUID(),reconciliationId=UUID.randomUUID();
        final Clock clock=Clock.fixed(Instant.parse("2026-07-13T10:00:00Z"),ZoneOffset.UTC);
        final CardTerminalConfiguration configuration=new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC,true,true,"Terminal","ref",1,"a".repeat(64),Map.of());
        final PaymentTerminalOperationRepository operations=mock(PaymentTerminalOperationRepository.class);
        final CardTerminalConfigurationReader configurations=mock(CardTerminalConfigurationReader.class);
        final CardTerminalGateway gateway=mock(CardTerminalGateway.class);
        final CurrentOrganization organization=mock(CurrentOrganization.class);
        final PaymentTerminalReconciliationService coordinator=mock(PaymentTerminalReconciliationService.class);
        Fixture(){
            var store=mock(Store.class);var company=mock(Company.class);when(store.getId()).thenReturn(storeId);when(store.getEmpresa()).thenReturn(company);
            when(company.getId()).thenReturn(companyId);when(organization.currentStore()).thenReturn(store);when(organization.currentCompany()).thenReturn(company);
            when(configurations.required(terminalId)).thenReturn(configuration);when(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC,true)).thenReturn(true);
            when(gateway.capabilities()).thenReturn(Set.of(PaymentTerminalCapability.RECONCILIATION));when(operations.reconciliationTotal(any(),any(),any(),any(),any())).thenReturn(BigDecimal.TEN);
        }
        PaymentTerminalOperationsService service(){return new PaymentTerminalOperationsService(operations,mock(PaymentTerminalAdjustmentService.class),
                mock(PaymentTerminalOperationService.class),configurations,List.of(gateway),clock,organization,mock(PaymentTerminalReceiptRepository.class),
                coordinator,mock(DocumentService.class));}
    }
}
