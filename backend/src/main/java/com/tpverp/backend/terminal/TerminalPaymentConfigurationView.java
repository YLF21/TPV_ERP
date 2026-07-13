package com.tpverp.backend.terminal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TerminalPaymentConfigurationView(
        UUID terminalId,
        TerminalPaymentRulesView rules,
        List<ProviderDescriptor> providerDescriptors,
        PaymentConfigurationView configuration) {

    public static TerminalPaymentConfigurationView from(
            Terminal terminal,
            StorePaymentConfiguration rules,
            TerminalPaymentConfiguration configuration) {
        return new TerminalPaymentConfigurationView(
                terminal.getId(),
                TerminalPaymentRulesView.from(rules),
                ProviderDescriptor.allowed(rules),
                PaymentConfigurationView.from(configuration));
    }

    public record ProviderField(String key, String label, String type, boolean required,
            List<PaymentTerminalMode> modes, List<String> options) {}

    public record ProviderDescriptor(PaymentTerminalProvider provider, String displayName,
            List<PaymentTerminalMode> supportedModes, boolean liveAvailable, String unavailableReason,
            List<PaymentTerminalCapability> capabilities, List<ProviderField> fieldSchemas) {
        private static final List<PaymentTerminalCapability> COMMON_CAPABILITIES = List.of(
                PaymentTerminalCapability.PAIRING, PaymentTerminalCapability.CONNECTION_TEST,
                PaymentTerminalCapability.CHARGE, PaymentTerminalCapability.QUERY,
                PaymentTerminalCapability.VOID, PaymentTerminalCapability.REFUND,
                PaymentTerminalCapability.RECEIPT, PaymentTerminalCapability.RECONCILIATION);
        private static final ProviderField SIMULATOR_OUTCOME = new ProviderField(
                "simulatorOutcome", "settings.paymentTerminal.outcome", "SELECT", false,
                List.of(PaymentTerminalMode.SIMULATED),
                List.of("APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR"));

        static List<ProviderDescriptor> allowed(StorePaymentConfiguration rules) {
            var allowed = java.util.Set.of(rules.getAllowedPaymentTerminalProviders().split(","));
            return java.util.Arrays.stream(PaymentTerminalProvider.values())
                    .filter(provider -> provider != PaymentTerminalProvider.NONE && allowed.contains(provider.name()))
                    .map(provider -> new ProviderDescriptor(provider, displayName(provider),
                            List.of(PaymentTerminalMode.SIMULATED, PaymentTerminalMode.LIVE), false,
                            "SDK_NOT_INSTALLED", COMMON_CAPABILITIES, fields(provider)))
                    .toList();
        }

        private static List<ProviderField> fields(PaymentTerminalProvider provider) {
            return provider == PaymentTerminalProvider.REDSYS_TPV_PC
                    ? List.of(SIMULATOR_OUTCOME, new ProviderField("ip", "settings.paymentTerminal.field.ip", "TEXT", false,
                            List.of(PaymentTerminalMode.LIVE), List.of()))
                    : List.of(SIMULATOR_OUTCOME);
        }

        private static String displayName(PaymentTerminalProvider provider) {
            return switch (provider) {
                case REDSYS_TPV_PC -> "Redsys TPV-PC";
                case PAYTEF -> "PAYTEF";
                case PAYCOMET -> "PAYCOMET";
                case GLOBAL_PAYMENTS -> "Global Payments";
                case NONE -> "";
            };
        }
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
            Map<String, String> providerParameters,
            boolean secretConfigured,
            Integer secretVersion,
            String pairingStatus) {

        static PaymentConfigurationView from(TerminalPaymentConfiguration configuration) {
            return new PaymentConfigurationView(
                    configuration.getCardMode(),
                    configuration.getProvider(),
                    configuration.getDisplayName(),
                    configuration.isEnabled(),
                    configuration.isTestMode(),
                    configuration.getLastConnectionTestAt(),
                    configuration.getLastConnectionStatus(),
                    safeProviderParameters(configuration),
                    opaqueReference(configuration) != null && configuration.getSecretReferenceVersion() != null,
                    opaqueReference(configuration) == null ? null : configuration.getSecretReferenceVersion(), null);
        }

        private static String opaqueReference(TerminalPaymentConfiguration configuration) {
            var reference=configuration.getSecretReference();
            return reference != null && reference.matches("pts_[a-f0-9]{32}") ? reference : null;
        }

        private static Map<String, String> safeProviderParameters(TerminalPaymentConfiguration configuration) {
            var safe = new java.util.LinkedHashMap<String, String>();
            var parameters = configuration.getProviderParameters();
            if (parameters.containsKey("simulatorOutcome")) safe.put("simulatorOutcome", parameters.get("simulatorOutcome"));
            if (configuration.getProvider() == PaymentTerminalProvider.REDSYS_TPV_PC && parameters.containsKey("ip")) {
                safe.put("ip", parameters.get("ip"));
            }
            return Map.copyOf(safe);
        }
    }
}
