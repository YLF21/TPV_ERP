package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTerminalGatewayContractTest {
    static Stream<PaymentTerminalProvider> providers() { return Stream.of(PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalProvider.PAYTEF, PaymentTerminalProvider.PAYCOMET, PaymentTerminalProvider.GLOBAL_PAYMENTS); }
    static Stream<Arguments> providersAndTerminalStatuses() {
        return providers().flatMap(provider -> Stream.of(
                PaymentTerminalOperationStatus.APPROVED, PaymentTerminalOperationStatus.DECLINED,
                PaymentTerminalOperationStatus.CANCELLED, PaymentTerminalOperationStatus.REFUNDED,
                PaymentTerminalOperationStatus.PARTIALLY_REFUNDED, PaymentTerminalOperationStatus.ERROR,
                PaymentTerminalOperationStatus.REVIEW_REQUIRED)
                .map(status -> Arguments.of(provider,status)));
    }

    @ParameterizedTest @MethodSource("providers")
    void exposesCommonCapabilities(PaymentTerminalProvider provider) {
        assertThat(gateway(provider).capabilities()).containsExactlyInAnyOrder(PaymentTerminalCapability.values());
    }

    @ParameterizedTest @MethodSource("providers")
    void normalizesChargeOutcomes(PaymentTerminalProvider provider) {
        assertThat(charge(provider,"APPROVED").status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(charge(provider,"DECLINED").status()).isEqualTo(PaymentTerminalOperationStatus.DECLINED);
        assertThat(charge(provider,"TIMEOUT").status()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);
        assertThat(charge(provider,"ERROR").status()).isEqualTo(PaymentTerminalOperationStatus.ERROR);
    }

    @ParameterizedTest @MethodSource("providers")
    void supportsQueryVoidPartialRefundReceiptAndReconciliation(PaymentTerminalProvider provider) {
        var gateway=gateway(provider); var operationId=UUID.fromString("11111111-1111-1111-1111-111111111111"); var context=context(provider,"APPROVED");
        var charge=gateway.charge(new PaymentTerminalChargeCommand(operationId,new BigDecimal("12.34")),context);
        var pairing=new PaymentTerminalPairCommand(UUID.randomUUID());
        assertThat(gateway.pair(pairing,context).code()).isEqualTo("PAIRED");
        assertThat(gateway.pairingStatus(pairing,context).code()).isEqualTo("PAIRED");
        assertThat(gateway.query(new PaymentTerminalQueryCommand(operationId,charge.reference()),context).status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(gateway.voidAuthorization(new PaymentTerminalVoidCommand(UUID.randomUUID(),operationId,charge.reference()),context).status()).isEqualTo(PaymentTerminalOperationStatus.CANCELLED);
        assertThat(gateway.refund(new PaymentTerminalRefundCommand(UUID.randomUUID(),operationId,new BigDecimal("2.34"),charge.reference()),context).status()).isEqualTo(PaymentTerminalOperationStatus.PARTIALLY_REFUNDED);
        assertThat(gateway.receipt(new PaymentTerminalReceiptCommand(operationId,charge.reference()),context).text()).contains(provider.name()).doesNotContain("PAN","CVV");
        assertThat(gateway.reconcile(new PaymentTerminalReconciliationCommand(UUID.randomUUID()),context).status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
    }

    @ParameterizedTest @MethodSource("providers")
    void simulatorOnlyQueriesPairingThatWasStarted(PaymentTerminalProvider provider) {
        var gateway=gateway(provider);var context=context(provider,"APPROVED");var pairing=new PaymentTerminalPairCommand(UUID.randomUUID());
        assertThat(gateway.pairingStatus(pairing,context).code()).isEqualTo("PAIRING_NOT_FOUND");
        assertThat(gateway.pair(pairing,context).code()).isEqualTo("PAIRED");
        assertThat(gateway.pairingStatus(pairing,context).code()).isEqualTo("PAIRED");
    }

    @ParameterizedTest @MethodSource("providers")
    void queryPreservesChargeOutcomeUntilAnExplicitQueryOutcomeResolvesIt(PaymentTerminalProvider provider) {
        var gateway=gateway(provider);
        var operationId=UUID.randomUUID();
        var timeout=gateway.charge(new PaymentTerminalChargeCommand(operationId,BigDecimal.ONE),context(provider,"TIMEOUT"));
        assertThat(timeout.status()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);

        var changedChargeSetting=context(provider,"APPROVED");
        assertThat(gateway.query(new PaymentTerminalQueryCommand(operationId,timeout.reference()),changedChargeSetting).status())
                .isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);

        var resolved=context(provider,"TIMEOUT",Map.of("simulatorQueryOutcome","APPROVED"));
        assertThat(gateway.query(new PaymentTerminalQueryCommand(operationId,timeout.reference()),resolved).status())
                .isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(gateway.query(new PaymentTerminalQueryCommand(operationId,timeout.reference()),context(provider,"TIMEOUT")).status())
                .isEqualTo(PaymentTerminalOperationStatus.APPROVED);
    }

    @ParameterizedTest @MethodSource("providersAndTerminalStatuses")
    void queryCannotOverwriteTerminalStatus(PaymentTerminalProvider provider,PaymentTerminalOperationStatus terminalStatus) {
        var gateway=gateway(provider);
        var operationId=UUID.randomUUID();
        var original=gateway.charge(new PaymentTerminalChargeCommand(operationId,BigDecimal.ONE),context(provider,terminalStatus.name()));
        assertThat(original.status()).isEqualTo(terminalStatus);

        var attemptedOverwrite=context(provider,terminalStatus.name(),Map.of("simulatorQueryOutcome","APPROVED"));
        if (terminalStatus==PaymentTerminalOperationStatus.APPROVED) {
            attemptedOverwrite=context(provider,terminalStatus.name(),Map.of("simulatorQueryOutcome","DECLINED"));
        }
        assertThat(gateway.query(new PaymentTerminalQueryCommand(operationId,original.reference()),attemptedOverwrite).status())
                .isEqualTo(terminalStatus);
    }

    @ParameterizedTest @MethodSource("providers")
    void liveStubRejectsEveryOperationAndExposesNoCapabilities(PaymentTerminalProvider provider) {
        var gateway=new UnavailableLivePaymentTerminalGateway(provider);
        var context=liveContext(provider);
        var operationId=UUID.randomUUID();
        var pairId=UUID.randomUUID();
        var results=Stream.of(
                gateway.pair(new PaymentTerminalPairCommand(pairId),context),
                gateway.pairingStatus(new PaymentTerminalPairCommand(pairId),context),
                gateway.charge(new PaymentTerminalChargeCommand(operationId,BigDecimal.ONE),context),
                gateway.query(new PaymentTerminalQueryCommand(operationId,"reference"),context),
                gateway.voidAuthorization(new PaymentTerminalVoidCommand(UUID.randomUUID(),operationId,"reference"),context),
                gateway.refund(new PaymentTerminalRefundCommand(UUID.randomUUID(),operationId,BigDecimal.ONE,"reference"),context),
                gateway.reconcile(new PaymentTerminalReconciliationCommand(UUID.randomUUID()),context));
        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.ERROR);
            assertThat(result.code()).isEqualTo("SDK_NOT_INSTALLED");
            assertThat(result.authorization()).isNull();
        });
        assertThat(gateway.receipt(new PaymentTerminalReceiptCommand(operationId,"reference"),context))
                .extracting(PaymentTerminalReceipt::status,PaymentTerminalReceipt::code)
                .containsExactly(PaymentTerminalOperationStatus.ERROR,"SDK_NOT_INSTALLED");
        assertThat(gateway.supports(provider,false)).isTrue();
        assertThat(gateway.supports(provider,true)).isFalse();
        assertThat(gateway.capabilities()).contains(PaymentTerminalCapability.PAIRING);
    }

    @Test
    void gatewayContextCarriesValidatedNonSecretConfigurationIdentityAndKeepsLegacyConstructor() {
        var context=new PaymentTerminalGatewayContext(UUID.randomUUID(),PaymentTerminalProvider.PAYTEF,
                PaymentTerminalMode.SIMULATED,"EUR","key","config:terminal:paytef",3L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",Map.of());
        assertThat(context.configurationReference()).isEqualTo("config:terminal:paytef");
        assertThat(context.configurationVersion()).isEqualTo(3L);
        assertThat(context.configurationHash()).hasSize(64);

        var legacy=new PaymentTerminalGatewayContext(UUID.randomUUID(),PaymentTerminalProvider.REDSYS_TPV_PC,
                PaymentTerminalMode.SIMULATED,"EUR","legacy",Map.of());
        assertThat(legacy.configurationReference()).isEqualTo("legacy:redsys");
        assertThat(legacy.configurationVersion()).isZero();
        assertThat(legacy.configurationHash()).isNull();

        assertThatThrownBy(() -> new PaymentTerminalGatewayContext(UUID.randomUUID(),PaymentTerminalProvider.PAYTEF,
                PaymentTerminalMode.SIMULATED,"EUR","key","secret:raw-password",1L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTerminalGatewayContext(UUID.randomUUID(),PaymentTerminalProvider.PAYTEF,
                PaymentTerminalMode.SIMULATED,"EUR","key","config:paytef",1L,"short",Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTerminalGatewayContext(UUID.randomUUID(),PaymentTerminalProvider.PAYTEF,
                PaymentTerminalMode.SIMULATED,"EUR","key","config:paytef",1L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Map.of("apiPassword","must-not-cross-the-gateway-boundary")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CardTerminalGateway gateway(PaymentTerminalProvider provider) { return switch(provider) { case REDSYS_TPV_PC -> new RedsysSimulatorGateway(); case PAYTEF -> new PaytefSimulatorGateway(); case PAYCOMET -> new PaycometSimulatorGateway(); case GLOBAL_PAYMENTS -> new GlobalPaymentsSimulatorGateway(); default -> throw new IllegalArgumentException(); }; }
    private static PaymentTerminalResult charge(PaymentTerminalProvider provider,String outcome) { return gateway(provider).charge(new PaymentTerminalChargeCommand(UUID.randomUUID(),BigDecimal.ONE),context(provider,outcome)); }
    private static PaymentTerminalGatewayContext context(PaymentTerminalProvider provider,String outcome) { return context(provider,outcome,Map.of()); }
    private static PaymentTerminalGatewayContext context(PaymentTerminalProvider provider,String outcome,Map<String,String> extra) { var parameters=new java.util.HashMap<String,String>(); parameters.put("simulatorOutcome",outcome); parameters.putAll(extra); return new PaymentTerminalGatewayContext(UUID.fromString("33333333-3333-3333-3333-333333333333"),provider,PaymentTerminalMode.SIMULATED,"EUR","key",parameters); }
    private static PaymentTerminalGatewayContext liveContext(PaymentTerminalProvider provider) { return new PaymentTerminalGatewayContext(UUID.randomUUID(),provider,PaymentTerminalMode.LIVE,"EUR","key","config:"+provider.name().toLowerCase(),1L,"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",Map.of()); }
}
