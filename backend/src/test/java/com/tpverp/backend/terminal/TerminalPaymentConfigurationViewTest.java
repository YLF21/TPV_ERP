package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalPaymentConfigurationViewTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesStableRulesAndSafeProviderParametersOnly() throws Exception {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys",
                true, true, Map.of("simulatorOutcome", "approved", "ip", "192.168.1.50"),
                "pts_0123456789abcdef0123456789abcdef",1));

        var json = mapper.valueToTree(TerminalPaymentConfigurationView.from(
                terminal, new StorePaymentConfiguration(terminal.getTienda()), configuration));

        assertThat(json.at("/rules/cardManualEnabled").asBoolean()).isTrue();
        assertThat(json.at("/rules/integratedCardEnabled").asBoolean()).isTrue();
        assertThat(json.at("/rules/allowedPaymentTerminalProviders").isArray()).isTrue();
        assertThat(json.at("/configuration/providerParameters/simulatorOutcome").asText())
                .isEqualTo("APPROVED");
        assertThat(json.at("/configuration/providerParameters/ip").asText()).isEqualTo("192.168.1.50");
        assertThat(json.toString()).doesNotContain("pts_0123456789abcdef0123456789abcdef");
    }

    @Test
    void exposesConnectionStatusUsingDocumentedOkErrorValues() {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.recordConnectionTest(true, java.time.Instant.parse("2026-07-04T10:15:30Z"));
        assertThat(TerminalPaymentConfigurationView.from(
                terminal, new StorePaymentConfiguration(terminal.getTienda()), configuration)
                .configuration().lastConnectionStatus()).isEqualTo("OK");
        configuration.recordConnectionTest(false, java.time.Instant.parse("2026-07-04T10:16:30Z"));
        assertThat(TerminalPaymentConfigurationView.from(
                terminal, new StorePaymentConfiguration(terminal.getTienda()), configuration)
                .configuration().lastConnectionStatus()).isEqualTo("ERROR");
    }

    @Test
    void exposesFourSafeProviderDescriptorsAndNeverRehydratesSecretReferences() throws Exception {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.PAYTEF, "Paytef",
                true, true, Map.of("simulatorOutcome", "APPROVED"),
                "pts_0123456789abcdef0123456789abcdef", 1));

        var json = mapper.valueToTree(TerminalPaymentConfigurationView.from(
                terminal, new StorePaymentConfiguration(terminal.getTienda()), configuration));

        assertThat(json.at("/providerDescriptors").size()).isEqualTo(4);
        assertThat(json.at("/providerDescriptors/0/capabilities").isArray()).isTrue();
        assertThat(json.at("/providerDescriptors/0/supportedModes").toString()).contains("SIMULATED", "LIVE");
        assertThat(json.at("/providerDescriptors/0/liveAvailable").asBoolean()).isFalse();
        assertThat(json.at("/providerDescriptors/0/unavailableReason").asText()).isEqualTo("SDK_NOT_INSTALLED");
        assertThat(json.at("/providerDescriptors/0/fieldSchemas/0/key").asText()).isEqualTo("simulatorOutcome");
        assertThat(json.toString()).doesNotContain("pts_0123456789abcdef0123456789abcdef");
        assertThat(json.at("/configuration/secretConfigured").asBoolean()).isTrue();
    }

    @Test
    void rehydratesAllowedNonSensitiveIpParameterInTheConfigurationView() {
        var terminal = terminal();
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, "Redsys",
                true, false, Map.of("ip", "10.0.0.25"), null));

        var view = TerminalPaymentConfigurationView.from(
                terminal, new StorePaymentConfiguration(terminal.getTienda()), configuration);

        assertThat(view.configuration().providerParameters()).containsExactlyEntriesOf(Map.of("ip", "10.0.0.25"));
    }

    private Terminal terminal() {
        var company = new Company("B12345678", "Demo SL", Map.of(
                "linea1", "Calle A", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var store = new Store(company, "Caja", Map.of(
                "linea1", "Calle A", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"),
                "hash", "Atlantic/Canary", "EUR", "es-ES");
        return new Terminal(store, "CAJA 1", TerminalType.TERMINAL_VENTA, "credential");
    }
}
