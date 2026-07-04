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
        this.providerParameters = safeProviderParameters(command.providerParameters());
        this.secretReference = optional(command.secretReference());
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

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, String> safeProviderParameters(Map<String, String> parameters) {
        var safe = new LinkedHashMap<String, String>();
        Objects.requireNonNullElse(parameters, Map.<String, String>of()).forEach((key, value) -> {
            var normalized = Objects.requireNonNull(key, "key").trim();
            var lower = normalized.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("password")
                    || lower.contains("secret")
                    || lower.contains("token")
                    || lower.equals("apikey")
                    || lower.equals("api_key")) {
                throw new IllegalArgumentException("message.payment_terminal.sensitive_parameter_not_allowed");
            }
            safe.put(normalized, value);
        });
        return safe;
    }
}
