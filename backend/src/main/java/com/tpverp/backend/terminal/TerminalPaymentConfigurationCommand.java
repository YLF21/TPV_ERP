package com.tpverp.backend.terminal;

import java.util.Map;

public record TerminalPaymentConfigurationCommand(
        PaymentCardMode cardMode,
        PaymentTerminalProvider provider,
        String displayName,
        boolean enabled,
        boolean testMode,
        Map<String, String> providerParameters,
        String secretReference) {
}
