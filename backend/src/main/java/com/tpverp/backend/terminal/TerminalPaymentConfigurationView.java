package com.tpverp.backend.terminal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TerminalPaymentConfigurationView(
        UUID terminalId,
        TerminalPaymentRulesView rules,
        PaymentConfigurationView configuration) {

    public static TerminalPaymentConfigurationView from(
            Terminal terminal,
            StorePaymentConfiguration rules,
            TerminalPaymentConfiguration configuration) {
        return new TerminalPaymentConfigurationView(
                terminal.getId(),
                TerminalPaymentRulesView.from(rules),
                PaymentConfigurationView.from(configuration));
    }

    public record TerminalPaymentRulesView(
            boolean cardManualEnabled,
            boolean cardManualReferenceRequired,
            boolean integratedCardEnabled,
            boolean manualFallbackEnabled,
            List<String> allowedPaymentTerminalProviders) {

        static TerminalPaymentRulesView from(StorePaymentConfiguration rules) {
            return new TerminalPaymentRulesView(
                    rules.isCardManualEnabled(),
                    rules.isCardManualReferenceRequired(),
                    rules.isIntegratedCardEnabled(),
                    rules.isManualFallbackEnabled(),
                    List.of(rules.getAllowedPaymentTerminalProviders().split(",")));
        }
    }

    public record PaymentConfigurationView(
            PaymentCardMode cardMode,
            PaymentTerminalProvider provider,
            String displayName,
            boolean enabled,
            boolean testMode,
            Instant lastConnectionTestAt,
            String lastConnectionStatus,
            boolean secretConfigured) {

        static PaymentConfigurationView from(TerminalPaymentConfiguration configuration) {
            return new PaymentConfigurationView(
                    configuration.getCardMode(),
                    configuration.getProvider(),
                    configuration.getDisplayName(),
                    configuration.isEnabled(),
                    configuration.isTestMode(),
                    configuration.getLastConnectionTestAt(),
                    configuration.getLastConnectionStatus(),
                    configuration.getSecretReference() != null);
        }
    }
}
