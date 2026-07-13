package com.tpverp.backend.terminal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardTerminalConfigurationReader {
    private final TerminalPaymentConfigurationRepository configurations; private final TerminalRepository terminals;
    private final StorePaymentConfigurationRepository storeConfigurations; private final Clock clock;
    public CardTerminalConfigurationReader(TerminalPaymentConfigurationRepository configurations,TerminalRepository terminals,
            StorePaymentConfigurationRepository storeConfigurations,Clock clock){this.configurations=configurations;this.terminals=terminals;this.storeConfigurations=storeConfigurations;this.clock=clock;}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public CardTerminalConfiguration required(UUID terminalId){return CardTerminalConfiguration.from(configurations.findByTerminalId(terminalId)
            .orElseThrow(()->new IllegalStateException("El datafono no esta configurado")));}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public TerminalPaymentConfigurationView recordAndView(UUID terminalId,boolean success){var terminal=terminals.findById(terminalId).orElseThrow();var config=configurations.findByTerminalId(terminalId).orElseThrow();config.recordConnectionTest(success,clock.instant());var store=storeConfigurations.findByStoreId(terminal.getTienda().getId()).orElseGet(()->new StorePaymentConfiguration(terminal.getTienda()));return TerminalPaymentConfigurationView.from(terminal,store,config);}
}
