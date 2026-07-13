package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalOperationsReceiptTest {
    @Test
    void firstReceiptResponseIsTheSanitizedCopyThatWasPersisted() {
        var operationId=UUID.randomUUID();var terminalId=UUID.randomUUID();var storeId=UUID.randomUUID();var now=Instant.EPOCH;
        var configuration=new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC,true,true,"TPV","ref",1,"a".repeat(64),Map.of());
        var operation=PaymentTerminalOperation.reserve(operationId,terminalId,storeId,configuration.provider(),
                PaymentTerminalMode.SIMULATED,PaymentTerminalOperationType.CHARGE,null,"receipt","b".repeat(64),
                BigDecimal.ONE,configuration.configurationHash(),configuration.configurationVersion(),now);
        var operations=mock(PaymentTerminalOperationRepository.class);when(operations.findById(operationId)).thenReturn(Optional.of(operation));
        var configurations=mock(CardTerminalConfigurationReader.class);when(configurations.required(terminalId)).thenReturn(configuration);
        var gateway=mock(CardTerminalGateway.class);when(gateway.supports(configuration.provider(),true)).thenReturn(true);
        when(gateway.capabilities()).thenReturn(Set.of(PaymentTerminalCapability.RECEIPT));
        when(gateway.receipt(any(),any())).thenReturn(new PaymentTerminalReceipt(PaymentTerminalOperationStatus.APPROVED,"OK",
                "Tarjeta: 4548 8120 4940 0004"));
        var receipts=mock(PaymentTerminalReceiptRepository.class);when(receipts.findByOperationId(operationId)).thenReturn(Optional.empty());
        var organization=mock(CurrentOrganization.class);var store=mock(Store.class);when(store.getId()).thenReturn(storeId);when(organization.currentStore()).thenReturn(store);
        var service=new PaymentTerminalOperationsService(operations,mock(PaymentTerminalAdjustmentService.class),
                mock(PaymentTerminalOperationService.class),configurations,List.of(gateway),Clock.fixed(now,ZoneOffset.UTC),organization,
                receipts,mock(PaymentTerminalReconciliationService.class),
                mock(com.tpverp.backend.document.DocumentService.class));

        var response=service.receipt(operationId);

        assertThat(response.text()).isEqualTo("Tarjeta: ****0004");
        verify(receipts).save(any(PaymentTerminalReceiptRecord.class));
    }
}
