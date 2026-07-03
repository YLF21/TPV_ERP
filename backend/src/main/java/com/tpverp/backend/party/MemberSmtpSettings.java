package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_smtp_settings")
public class MemberSmtpSettings {

    @Id
    @Column(name = "empresa_id")
    private UUID companyId;
    private boolean enabled;
    @Column(nullable = false)
    private String host;
    @Column(nullable = false)
    private int port;
    private String username;
    private String password;
    @Column(name = "from_email", nullable = false)
    private String fromEmail;
    @Column(name = "from_name")
    private String fromName;
    @Column(name = "start_tls", nullable = false)
    private boolean startTls;
    @Column(name = "ssl_enabled", nullable = false)
    private boolean sslEnabled;
    @Version
    private long version;

    protected MemberSmtpSettings() {
    }

    public MemberSmtpSettings(UUID companyId, MemberSmtpSettingsCommand command) {
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        update(command);
    }

    public void update(MemberSmtpSettingsCommand command) {
        enabled = command.enabled();
        host = PartyValues.required(command.host(), "host");
        if (command.port() < 1 || command.port() > 65535) {
            throw new IllegalArgumentException("message.smtp.port_invalid");
        }
        port = command.port();
        username = PartyValues.optional(command.username());
        if (command.password() != null && !command.password().isBlank()) {
            password = command.password();
        }
        fromEmail = PartyValues.required(command.fromEmail(), "fromEmail");
        fromName = PartyValues.optional(command.fromName());
        startTls = command.startTls();
        sslEnabled = command.sslEnabled();
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getFromEmail() {
        return fromEmail;
    }

    public String getFromName() {
        return fromName;
    }

    public boolean isStartTls() {
        return startTls;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }
}
