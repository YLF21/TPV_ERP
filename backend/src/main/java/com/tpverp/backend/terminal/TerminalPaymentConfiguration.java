package com.tpverp.backend.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "configuracion_pago_terminal")
public class TerminalPaymentConfiguration {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terminal_id", nullable = false, unique = true)
    private Terminal terminal;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_mode", nullable = false, length = 16)
    private PaymentCardMode cardMode = PaymentCardMode.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentTerminalProvider provider = PaymentTerminalProvider.NONE;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "test_mode", nullable = false)
    private boolean testMode;

    @Column(name = "last_connection_test_at")
    private Instant lastConnectionTestAt;

    @Column(name = "last_connection_status", length = 16)
    private String lastConnectionStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_parameters", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> providerParameters = new LinkedHashMap<>();

    @Column(name = "secret_reference", length = 255)
    private String secretReference;

    @Column(name = "secret_reference_version")
    private Integer secretReferenceVersion;

    @Version
    private long version;

    protected TerminalPaymentConfiguration() {
    }

    private TerminalPaymentConfiguration(Terminal terminal) {
        this.id = UUID.randomUUID();
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    public static TerminalPaymentConfiguration manual(Terminal terminal) {
        return new TerminalPaymentConfiguration(terminal);
    }

    public void configure(TerminalPaymentConfigurationCommand command) {
        var previousProvider=this.provider;
        this.cardMode = Objects.requireNonNull(command.cardMode(), "cardMode");
        this.provider = Objects.requireNonNull(command.provider(), "provider");
        if (cardMode == PaymentCardMode.MANUAL && provider != PaymentTerminalProvider.NONE) {
            throw new IllegalArgumentException("message.payment_terminal.manual_provider_must_be_none");
        }
        if (cardMode == PaymentCardMode.INTEGRATED && provider == PaymentTerminalProvider.NONE) {
            throw new IllegalArgumentException("message.payment_terminal.integrated_provider_required");
        }
        this.displayName = optional(command.displayName());
        this.enabled = command.enabled();
        this.testMode = command.testMode();
        this.providerParameters = safeProviderParameters(command.providerParameters(), provider, testMode);
        if (command.secretReference() != null && !command.secretReference().isBlank()) {
            assignSecretReference(command.secretReference(),Objects.requireNonNull(command.secretVersion(),"secretVersion"));
        } else if (cardMode != PaymentCardMode.INTEGRATED || provider != previousProvider) {
            clearSecretReference();
        }
    }

    public void recordConnectionTest(boolean success, Instant when) {
        this.lastConnectionTestAt = Objects.requireNonNull(when, "when");
        this.lastConnectionStatus = success ? "OK" : "ERROR";
    }

    public UUID getId() {
        return id;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public PaymentCardMode getCardMode() {
        return cardMode;
    }

    public PaymentTerminalProvider getProvider() {
        return provider;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public Instant getLastConnectionTestAt() {
        return lastConnectionTestAt;
    }

    public String getLastConnectionStatus() {
        return lastConnectionStatus;
    }

    public Map<String, String> getProviderParameters() {
        return Map.copyOf(providerParameters);
    }

    public String getSecretReference() {
        return secretReference;
    }

    public Integer getSecretReferenceVersion() { return secretReferenceVersion; }

    public void assignSecretReference(String reference, int version) {
        if (reference == null || !reference.matches("pts_[a-f0-9]{32}") || version < 1) {
            throw new IllegalArgumentException("message.payment_terminal.secret_reference_invalid");
        }
        this.secretReference = reference;
        this.secretReferenceVersion = version;
    }
    public void clearSecretReference(){this.secretReference=null;this.secretReferenceVersion=null;}

    public long getVersion() { return version; }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, String> safeProviderParameters(
            Map<String, String> parameters, PaymentTerminalProvider provider, boolean testMode) {
        var safe = new LinkedHashMap<String, String>();
        Objects.requireNonNullElse(parameters, Map.<String, String>of()).forEach((key, value) -> {
            var normalized = Objects.requireNonNull(key, "key").trim();
            if(PaymentTerminalSensitiveData.sensitiveKey(normalized))throw new IllegalArgumentException("message.payment_terminal.sensitive_parameter_not_allowed");
            if(normalized.equals("simulatorOutcome")&&!testMode)throw new IllegalArgumentException("message.payment_terminal.simulator_outcome_invalid");
            var allowed=(provider!=PaymentTerminalProvider.NONE&&testMode&&normalized.equals("simulatorOutcome"))
                    ||(provider==PaymentTerminalProvider.REDSYS_TPV_PC&&normalized.equals("ip"));
            if(!allowed)throw new IllegalArgumentException("message.payment_terminal.provider_parameter_not_allowed");
            safe.put(normalized, value);
        });
        var simulatorOutcome = safe.get("simulatorOutcome");
        if (simulatorOutcome != null) {
            var validContext = provider != PaymentTerminalProvider.NONE && testMode;
            var normalizedOutcome = simulatorOutcome.trim().toUpperCase(java.util.Locale.ROOT);
            if (!validContext || !java.util.Set.of("APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR", "PENDING", "ERROR")
                    .contains(normalizedOutcome)) {
                throw new IllegalArgumentException("message.payment_terminal.simulator_outcome_invalid");
            }
            safe.put("simulatorOutcome", normalizedOutcome);
        }
        return safe;
    }
}
