package com.tpverp.backend.terminal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.tpverp.backend.terminal.secrets.PaymentSecretStore;

@Service
public class TerminalPaymentConfigurationService {

    private final TerminalPaymentConfigurationRepository configurations;
    private final StorePaymentConfigurationRepository storeConfigurations;
    private final TerminalRepository terminals;
    private final CurrentTerminal currentTerminal;
    private final List<CardTerminalGateway> gateways;
    private final CardTerminalConfigurationReader gatewayConfigurations;
    private final Clock clock;
    private final PaymentSecretStore secretStore;

    public TerminalPaymentConfigurationService(
            TerminalPaymentConfigurationRepository configurations,
            StorePaymentConfigurationRepository storeConfigurations,
            TerminalRepository terminals,
            CurrentTerminal currentTerminal,
            List<CardTerminalGateway> gateways,
            CardTerminalConfigurationReader gatewayConfigurations,
            Clock clock) {this(configurations,storeConfigurations,terminals,currentTerminal,gateways,gatewayConfigurations,clock,null);}
    @Autowired
    public TerminalPaymentConfigurationService(
            TerminalPaymentConfigurationRepository configurations,
            StorePaymentConfigurationRepository storeConfigurations,
            TerminalRepository terminals,CurrentTerminal currentTerminal,List<CardTerminalGateway> gateways,
            CardTerminalConfigurationReader gatewayConfigurations,Clock clock,PaymentSecretStore secretStore) {
        this.configurations = configurations;
        this.storeConfigurations = storeConfigurations;
        this.terminals = terminals;
        this.currentTerminal = currentTerminal;
        this.gateways = List.copyOf(gateways);
        this.gatewayConfigurations = gatewayConfigurations;
        this.clock = clock;
        this.secretStore=secretStore;
    }

    @Transactional(readOnly = true)
    public TerminalPaymentConfigurationView current() {
        var terminal = currentTerminal();
        return decorate(TerminalPaymentConfigurationView.from(terminal, storeRules(terminal), terminalConfiguration(terminal)));
    }

    @Transactional
    public TerminalPaymentConfigurationView update(TerminalPaymentConfigurationCommand command) {
        var terminal = currentTerminal();
        var configuration = configurations.findByTerminalId(terminal.getId())
                .orElseGet(() -> configurations.save(TerminalPaymentConfiguration.manual(terminal)));
        if(command.secretReference()!=null&&!command.secretReference().isBlank()){
            if(secretStore==null)throw new IllegalStateException("message.payment_terminal.secret_store_unavailable");
            var metadata=secretStore.describe(command.secretReference());
            if(metadata.version()!=command.secretVersion()||!metadata.provider().equals(command.provider().name()))throw new IllegalArgumentException("message.payment_terminal.secret_reference_mismatch");
        } else if(configuration.getSecretReference()!=null&&configuration.getProvider()==command.provider()&&command.cardMode()==PaymentCardMode.INTEGRATED){
            if(secretStore==null)throw new IllegalStateException("message.payment_terminal.secret_store_unavailable");
            var metadata=secretStore.describe(configuration.getSecretReference());
            if(metadata.version()!=configuration.getSecretReferenceVersion()||!metadata.provider().equals(command.provider().name()))throw new IllegalArgumentException("message.payment_terminal.secret_reference_mismatch");
        }
        configuration.configure(command);
        return decorate(TerminalPaymentConfigurationView.from(terminal, storeRules(terminal), configuration));
    }

    public TerminalPaymentConfigurationView testConnection() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        var terminalId=currentTerminal.terminalId(authentication);
        var configuration=gatewayConfigurations.required(terminalId);
        var gateway = gateways.stream()
                .filter(candidate -> candidate.supports(configuration.provider(), configuration.testMode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("message.payment_terminal.gateway_not_available"));
        var result = gateway.testConnection(configuration);
        return decorate(gatewayConfigurations.recordAndView(terminalId,result.status()==PaymentTerminalOperationStatus.APPROVED));
    }

    public PaymentTerminalResult pair(UUID pairingId) {
        return pairing(pairingId, false);
    }

    public PaymentTerminalResult pairingStatus(UUID pairingId) {
        return pairing(pairingId, true);
    }

    private PaymentTerminalResult pairing(UUID pairingId, boolean statusOnly) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var terminalId = currentTerminal.terminalId(authentication);
        var persistent=configurations.findByTerminalId(terminalId);
        if(statusOnly&&(persistent.isEmpty()||!persistent.orElseThrow().matchesPairing(pairingId)))throw new IllegalStateException("message.payment_terminal.pairing_not_started");
        var configuration = gatewayConfigurations.required(terminalId);
        var gateway = gateways.stream()
                .filter(candidate -> candidate.supports(configuration.provider(), configuration.testMode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("message.payment_terminal.gateway_not_available"));
        var command = new PaymentTerminalPairCommand(pairingId);
        var context = gatewayContext(configuration, pairingId);
        if (!gateway.capabilities().contains(PaymentTerminalCapability.PAIRING)) {
            throw new IllegalStateException("message.payment_terminal.pairing_not_supported");
        }
        var result=statusOnly ? gateway.pairingStatus(command, context) : gateway.pair(command, context);
        persistent.ifPresent(entity->{entity.recordPairing(pairingId,result);configurations.save(entity);});
        return result;
    }

    private PaymentTerminalGatewayContext gatewayContext(CardTerminalConfiguration configuration, UUID operationId) {
        return new PaymentTerminalGatewayContext(configuration.terminalId(), configuration.provider(),
                configuration.testMode() ? PaymentTerminalMode.SIMULATED : PaymentTerminalMode.LIVE,
                "EUR", operationId.toString(), configuration.configurationReference(),
                configuration.configurationVersion(), configuration.configurationHash(), configuration.parameters());
    }

    private Terminal currentTerminal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var terminalId = currentTerminal.terminalId(authentication);
        return terminals.findById(terminalId)
                .orElseThrow(() -> new IllegalStateException("message.terminal.not_found"));
    }

    private TerminalPaymentConfiguration terminalConfiguration(Terminal terminal) {
        return configurations.findByTerminalId(terminal.getId())
                .orElseGet(() -> TerminalPaymentConfiguration.manual(terminal));
    }

    private StorePaymentConfiguration storeRules(Terminal terminal) {
        return storeConfigurations.findByStoreId(terminal.getTienda().getId())
                .orElseGet(() -> new StorePaymentConfiguration(terminal.getTienda()));
    }

    private TerminalPaymentConfigurationView decorate(TerminalPaymentConfigurationView view) {
        var capabilities = new java.util.EnumMap<PaymentTerminalProvider, java.util.Set<PaymentTerminalCapability>>(
                PaymentTerminalProvider.class);
        for (var provider : PaymentTerminalProvider.values()) {
            if (provider == PaymentTerminalProvider.NONE) continue;
            gateways.stream().filter(gateway -> gateway.supports(provider, false)).findFirst().ifPresent(gateway -> {
                try {
                    var advertised = gateway.capabilities();
                    if (advertised.contains(PaymentTerminalCapability.CHARGE)) capabilities.put(provider, advertised);
                } catch (RuntimeException ignored) {
                    // An unavailable local bridge must keep LIVE disabled in the configuration UI.
                }
            });
        }
        return view.withLiveCapabilities(capabilities);
    }
}
