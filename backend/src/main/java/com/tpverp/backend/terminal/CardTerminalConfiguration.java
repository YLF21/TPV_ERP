package com.tpverp.backend.terminal;

import java.util.Map;
import java.util.UUID;

public record CardTerminalConfiguration(
        UUID terminalId, UUID storeId, PaymentCardMode mode, PaymentTerminalProvider provider,
        boolean enabled, boolean testMode, String displayName, String configurationReference,
        long configurationVersion, String configurationHash, Map<String,String> parameters) {
    public CardTerminalConfiguration { parameters=Map.copyOf(parameters); }
    static CardTerminalConfiguration from(TerminalPaymentConfiguration entity){
        var parameters=entity.getOperationalProviderParameters();
        var reference="terminal-payment:"+entity.getId();
        var hash=ConfigurationFingerprint.sha256(entity.getProvider().name()+"|"+entity.isTestMode()+"|"+parameters);
        return new CardTerminalConfiguration(entity.getTerminal().getId(),entity.getTerminal().getTienda().getId(),entity.getCardMode(),entity.getProvider(),
                entity.isEnabled(),entity.isTestMode(),entity.getDisplayName(),reference,entity.getOperationalVersion(),hash,parameters);
    }

    /** Compatibility constructor retained for existing tests and callers. */
    public CardTerminalConfiguration(UUID terminalId,PaymentCardMode mode,PaymentTerminalProvider provider,
            boolean enabled,boolean testMode,String displayName,Map<String,String> parameters){
        this(terminalId,terminalId,mode,provider,enabled,testMode,displayName,"legacy:"+provider.name().toLowerCase(),0,
                ConfigurationFingerprint.sha256(provider.name()+"|"+testMode+"|"+parameters),parameters);
    }
}
