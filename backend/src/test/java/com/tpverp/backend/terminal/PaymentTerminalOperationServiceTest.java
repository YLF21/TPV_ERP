package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentTerminalOperationServiceTest {
    @Mock PaymentTerminalOperationRepository repository;
    @Mock CardTerminalConfigurationReader configurations;
    @Mock CardTerminalGateway paytef;
    private final Instant now=Instant.parse("2026-07-12T12:00:00Z");
    private PaymentTerminalOperationService service;
    private CardTerminalConfiguration configuration;
    private UUID terminal,store,operationId;

    @BeforeEach void setup(){
        terminal=UUID.randomUUID();store=UUID.randomUUID();operationId=UUID.randomUUID();
        configuration=new CardTerminalConfiguration(terminal,store,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,
                true,true,"PAYTEF","config:paytef",7,"b".repeat(64),Map.of("simulatorOutcome","APPROVED"));
        lenient().when(paytef.supports(PaymentTerminalProvider.PAYTEF,true)).thenReturn(true);
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        lenient().when(repository.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        service=new PaymentTerminalOperationService(repository,configurations,List.of(paytef),Clock.fixed(now,ZoneOffset.UTC));
    }

    @Test void routesToActualProviderAndPropagatesFullSimulatedConfiguration(){
        var persisted=new java.util.concurrent.atomic.AtomicReference<PaymentTerminalOperation>();
        when(repository.findByTerminalIdAndIdempotencyKey(terminal,operationId.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation->{persisted.set(invocation.getArgument(0));return persisted.get();});
        when(paytef.charge(any(PaymentTerminalChargeCommand.class),any(PaymentTerminalGatewayContext.class))).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"APPROVED","REF","AUTH","ok"));
        when(repository.findLockedById(operationId)).thenAnswer(invocation->Optional.of(persisted.get()));

        var result=service.charge(operationId,"a".repeat(64),new BigDecimal("10.00"),configuration);

        verify(repository).saveAndFlush(any());
        var context=ArgumentCaptor.forClass(PaymentTerminalGatewayContext.class);
        verify(paytef).charge(eq(new PaymentTerminalChargeCommand(operationId,new BigDecimal("10.00"))),context.capture());
        assertThat(context.getValue().provider()).isEqualTo(PaymentTerminalProvider.PAYTEF);
        assertThat(context.getValue().mode()).isEqualTo(PaymentTerminalMode.SIMULATED);
        assertThat(context.getValue().configurationReference()).isEqualTo("config:paytef");
        assertThat(context.getValue().configurationVersion()).isEqualTo(7);
        assertThat(context.getValue().configurationHash()).isEqualTo("b".repeat(64));
        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
    }

    @Test void replayNeverSendsSecondChargeAndHashConflictStopsIt(){
        var operation=operation("a".repeat(64));
        when(repository.findByTerminalIdAndIdempotencyKey(terminal,operationId.toString())).thenReturn(Optional.of(operation));
        assertThat(service.charge(operationId,"a".repeat(64),new BigDecimal("10.00"),configuration).status())
                .isEqualTo(PaymentTerminalOperationStatus.SENT);
        verify(paytef,never()).charge(any(PaymentTerminalChargeCommand.class),any(PaymentTerminalGatewayContext.class));
        assertThatThrownBy(()->service.charge(operationId,"c".repeat(64),new BigDecimal("10.00"),configuration))
                .hasMessageContaining("otra venta");
        verify(paytef,never()).charge(any(PaymentTerminalChargeCommand.class),any(PaymentTerminalGatewayContext.class));
    }

    @Test void recoveryQueriesTimeoutAndNeverRepeatsCharge(){
        var operation=operation("a".repeat(64));operation.timeout("TIMEOUT","incierto",now);
        when(repository.findLockedById(operationId)).thenReturn(Optional.of(operation));
        when(repository.findById(operationId)).thenReturn(Optional.of(operation));
        when(configurations.required(terminal)).thenReturn(configuration);
        when(paytef.capabilities()).thenReturn(Set.of(PaymentTerminalCapability.QUERY));
        when(paytef.query(any(),any())).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"APPROVED","REF","AUTH","ok"));

        var recovered=service.recover(operationId,UUID.randomUUID());

        assertThat(recovered.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(service.recover(operationId,UUID.randomUUID()).getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        verify(paytef).query(any(),any());verify(paytef,never()).charge(any(PaymentTerminalChargeCommand.class),any(PaymentTerminalGatewayContext.class));
    }

    @Test void crashAfterSentIsRecoveredOnlyByQuery(){
        var operation=operation("a".repeat(64));
        when(repository.findLockedById(operationId)).thenReturn(Optional.of(operation));when(repository.findById(operationId)).thenReturn(Optional.of(operation));
        when(configurations.required(terminal)).thenReturn(configuration);when(paytef.capabilities()).thenReturn(Set.of(PaymentTerminalCapability.QUERY));
        when(paytef.query(any(),any())).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"APPROVED","REF","AUTH","ok"));
        assertThat(service.recover(operationId,UUID.randomUUID()).getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        verify(paytef).query(any(),any());verify(paytef,never()).charge(any(PaymentTerminalChargeCommand.class),any(PaymentTerminalGatewayContext.class));
    }

    @Test void configurationMismatchGoesToReviewWithoutCallingProvider(){
        var operation=operation("a".repeat(64));operation.timeout("TIMEOUT","incierto",now);
        when(repository.findLockedById(operationId)).thenReturn(Optional.of(operation));when(configurations.required(terminal)).thenReturn(
                new CardTerminalConfiguration(terminal,store,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"changed","config:new",8,"c".repeat(64),Map.of()));
        assertThat(service.recover(operationId,UUID.randomUUID()).getStatus()).isEqualTo(PaymentTerminalOperationStatus.REVIEW_REQUIRED);
        verify(paytef,never()).query(any(),any());
    }

    @Test void boundedBackoffEventuallyRequiresManualReview(){
        var operation=operation("a".repeat(64));operation.timeout("TIMEOUT","incierto",now);
        when(repository.findLockedById(operationId)).thenReturn(Optional.of(operation));when(configurations.required(terminal)).thenReturn(configuration);
        when(paytef.capabilities()).thenReturn(Set.of(PaymentTerminalCapability.QUERY));
        when(paytef.query(any(),any())).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,"TIMEOUT",operationId.toString(),null,"still unknown"));
        for(int attempt=0;attempt<5;attempt++)service.recover(operationId,UUID.randomUUID());
        assertThat(operation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.REVIEW_REQUIRED);assertThat(operation.getRetryCount()).isEqualTo(4);
        verify(paytef,times(5)).query(any(),any());
    }

    @Test void recoverableQueryUsesSentSafetyThreshold(){service.recoverable(20);verify(repository).findRecoverable(eq(now),eq(now.minusSeconds(45)),any());}

    @Test void uniqueInsertRaceReloadsWinnerWithoutSecondGatewayCall(){var winner=operation("a".repeat(64));
        when(repository.findByTerminalIdAndIdempotencyKey(terminal,operationId.toString())).thenReturn(Optional.empty(),Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique race"));
        assertThat(service.charge(operationId,"a".repeat(64),new BigDecimal("10.00"),configuration).status()).isEqualTo(PaymentTerminalOperationStatus.SENT);
        verify(paytef,never()).charge(any(PaymentTerminalChargeCommand.class),any(PaymentTerminalGatewayContext.class));}

    @Test void fiveDocumentFailuresBecomeReviewRequiredWithoutBlockingTheWorker(){
        var approved=operation("a".repeat(64)); approved.approve("REF","AUTH",now.plusSeconds(1));
        when(repository.findLockedById(operationId)).thenReturn(Optional.of(approved));
        for(int failure=0;failure<5;failure++) service.documentFailure(operationId,"document unavailable");
        assertThat(approved.getStatus()).isEqualTo(PaymentTerminalOperationStatus.REVIEW_REQUIRED);
        assertThat(approved.getDocumentRetryCount()).isEqualTo(4);
        assertThat(approved.getProcessingOwner()).isNull();
    }

    @Test void finalizationRevalidatesTheIntegratedChargeUnderLock() {
        var approved=operation("a".repeat(64));approved.approve("REF","AUTH",now.plusSeconds(1));
        approved.recordRefund(new BigDecimal("10.00"),now.plusSeconds(2));
        when(repository.findLockedById(operationId)).thenReturn(Optional.of(approved));

        assertThatThrownBy(()->service.requireFinalizableApprovedCharge(operationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payment_operation_not_finalizable");
        verify(repository).findLockedById(operationId);
    }

    private PaymentTerminalOperation operation(String hash){var operation=PaymentTerminalOperation.reserve(operationId,terminal,store,
            PaymentTerminalProvider.PAYTEF,PaymentTerminalMode.SIMULATED,PaymentTerminalOperationType.CHARGE,null,
            operationId.toString(),hash,new BigDecimal("10.00"),"b".repeat(64),7,now);operation.markSent("SEND",now);return operation;}
}
