package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedsysSimulatorGatewayTest {

    private final RedsysSimulatorGateway gateway = new RedsysSimulatorGateway();

    @Test
    void approvesWithSafeSimulatedIdentifiers() {
        var configuration = configuration("APPROVED", true);
        var request = request(configuration, true);

        var result = gateway.charge(request, configuration);

        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(result.reference()).startsWith("SIM-").doesNotContain("4111", "CVV", "PAN");
        assertThat(result.authorization()).startsWith("SIMAUTH-").doesNotContain("4111", "CVV", "PAN");
    }

    @Test
    void declinesWithoutAuthorization() {
        var configuration = configuration("DECLINED", true);
        var result = gateway.charge(request(configuration, true), configuration);

        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.DECLINED);
        assertThat(result.authorization()).isNull();
    }

    @Test
    void reportsTimeoutWithoutAuthorization() {
        var configuration = configuration("TIMEOUT", true);
        var result = gateway.charge(request(configuration, true), configuration);

        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);
        assertThat(result.authorization()).isNull();
    }

    @Test
    void normalizesConnectionErrorToError() {
        var configuration = configuration("CONNECTION_ERROR", true);
        var result = gateway.charge(request(configuration, true), configuration);

        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.ERROR);
        assertThat(result.authorization()).isNull();
    }

    @Test
    void neverRunsOutsideTestMode() {
        assertThat(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC, false)).isFalse();

        var configuration = configuration("APPROVED", false);
        var result = gateway.charge(request(configuration, false), configuration);

        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.ERROR);
        assertThat(result.authorization()).isNull();
        assertThat(result.message()).contains("SDK");
    }

    @Test
    void testsConnectionUsingConfiguredOutcome() {
        assertThat(gateway.testConnection(configuration("APPROVED", true)).status())
                .isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(gateway.testConnection(configuration("CONNECTION_ERROR", true)).status())
                .isEqualTo(PaymentTerminalOperationStatus.ERROR);
    }

    @Test
    void sameCheckoutProducesExactlyTheSameResult() {
        var configuration = configuration("APPROVED", true);
        var request = request(configuration, true);

        assertThat(gateway.charge(request, configuration))
                .isEqualTo(gateway.charge(request, configuration));
    }

    @Test
    void neverApprovesAnUnavailableOrMismatchedConfiguration() {
        var disabled = configuration("APPROVED", true, false, PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC);
        var manual = configuration("APPROVED", true, true, PaymentCardMode.MANUAL,
                PaymentTerminalProvider.NONE);
        var otherProvider = configuration("APPROVED", true, true, PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.PAYTEF);
        var valid = configuration("APPROVED", true);
        var mismatched = new CardTerminalRequest(
                UUID.randomUUID(), UUID.randomUUID(), PaymentTerminalProvider.REDSYS_TPV_PC,
                BigDecimal.ONE, true);

        assertThat(gateway.charge(request(disabled, true), disabled).message()).contains("desactivada");
        assertThat(gateway.charge(request(manual, true), manual).message()).contains("integrado");
        assertThat(gateway.charge(request(otherProvider, true), otherProvider).message()).containsIgnoringCase("proveedor");
        assertThat(gateway.charge(mismatched, valid).message()).contains("terminal");
    }

    @Test
    void missingOrInvalidOutcomeIsAnError() {
        var missing = configuration(null, true);
        assertThat(gateway.charge(request(missing, true), missing).status())
                .isEqualTo(PaymentTerminalOperationStatus.ERROR);
        var invalid = configuration("SURPRISE", true);
        assertThat(gateway.charge(request(invalid, true), invalid).status())
                .isEqualTo(PaymentTerminalOperationStatus.ERROR);
    }

    @Test
    void requestRejectsMissingFieldsAndNonPositiveAmounts() {
        var id = UUID.randomUUID();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new CardTerminalRequest(null, id, PaymentTerminalProvider.REDSYS_TPV_PC, BigDecimal.ONE, true)))
                .isInstanceOf(NullPointerException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new CardTerminalRequest(id, null, PaymentTerminalProvider.REDSYS_TPV_PC, BigDecimal.ONE, true)))
                .isInstanceOf(NullPointerException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new CardTerminalRequest(id, id, null, BigDecimal.ONE, true)))
                .isInstanceOf(NullPointerException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new CardTerminalRequest(id, id, PaymentTerminalProvider.REDSYS_TPV_PC, null, true)))
                .isInstanceOf(NullPointerException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new CardTerminalRequest(id, id, PaymentTerminalProvider.REDSYS_TPV_PC, BigDecimal.ZERO, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new CardTerminalRequest(id, id, PaymentTerminalProvider.REDSYS_TPV_PC, BigDecimal.ONE.negate(), true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultNeverLeaksArbitraryProviderParameters() throws Exception {
        var configuration = configuration("APPROVED", true);
        var sensitive = "4111111111111111-CVV123-superSecret";
        var parameters=new LinkedHashMap<>(configuration.parameters());parameters.put("secretLike",sensitive);
        configuration=new CardTerminalConfiguration(configuration.terminalId(),configuration.mode(),configuration.provider(),configuration.enabled(),configuration.testMode(),configuration.displayName(),parameters);

        var result = gateway.charge(request(configuration, true), configuration);

        assertThat(result.toString()).doesNotContain(sensitive, "4111111111111111", "CVV123", "superSecret");
        assertThat(result.reference()).matches("SIM-[0-9A-F]{32}");
        assertThat(result.authorization()).matches("SIMAUTH-[0-9A-F]{8}");
    }

    private CardTerminalRequest request(CardTerminalConfiguration configuration, boolean testMode) {
        return new CardTerminalRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                configuration.terminalId(),
                PaymentTerminalProvider.REDSYS_TPV_PC,
                new BigDecimal("12.34"),
                testMode);
    }

    private CardTerminalConfiguration configuration(String outcome, boolean testMode) {
        return configuration(outcome, testMode, true, PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC);
    }

    private CardTerminalConfiguration configuration(
            String outcome,
            boolean testMode,
            boolean enabled,
            PaymentCardMode mode,
            PaymentTerminalProvider provider) {
        var company = new Company("B12345678", "Demo SL", Map.of(
                "linea1", "Calle A", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var store = new Store(company, "Caja", Map.of(
                "linea1", "Calle A", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"), "hash", "Atlantic/Canary", "EUR", "es-ES");
        var terminal = new Terminal(store, "CAJA 1", TerminalType.TERMINAL_VENTA, "credential");
        return new CardTerminalConfiguration(terminal.getId(),mode,provider,enabled,testMode,"Redsys",
                outcome==null?Map.of():Map.of("simulatorOutcome",outcome));
    }

    @SuppressWarnings("unchecked")
    private void injectProviderParameter(
            TerminalPaymentConfiguration configuration, String key, String value) throws Exception {
        Field field = TerminalPaymentConfiguration.class.getDeclaredField("providerParameters");
        field.setAccessible(true);
        var parameters = new LinkedHashMap<>((Map<String, String>) field.get(configuration));
        parameters.put(key, value);
        field.set(configuration, parameters);
    }
}
