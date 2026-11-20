package com.tpverp.backend.party;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.env.Environment;

@Service
public class MemberSmtpSettingsService {

    private final MemberSmtpSettingsRepository settings;
    private final CurrentOrganization organization;
    private final MemberCardSender sender;
    private final MailSecretCipher cipher;
    private final Environment environment;

    public MemberSmtpSettingsService(
            MemberSmtpSettingsRepository settings,
            CurrentOrganization organization,
            MemberCardSender sender, MailSecretCipher cipher, Environment environment) {
        this.settings = settings;
        this.organization = organization;
        this.sender = sender;
        this.cipher = cipher;
        this.environment = environment;
    }

    @Transactional(readOnly = true)
    public MemberSmtpSettingsView get() {
        return settings.findById(organization.currentCompany().getId())
                .map(this::view)
                .orElseGet(() -> new MemberSmtpSettingsView(false, false, null, 587, null, null, null,
                        true, false, false, cipher.isConfigured(), deliveryEnabled()));
    }

    @Transactional
    public MemberSmtpSettingsView update(MemberSmtpSettingsCommand command) {
        UUID companyId = organization.currentCompany().getId();
        String protectedPassword = command.password() == null || command.password().isBlank()
                ? null : cipher.encrypt(command.password(), companyId);
        var protectedCommand = new MemberSmtpSettingsCommand(command.enabled(), command.host(), command.port(),
                command.username(), protectedPassword, command.fromEmail(), command.fromName(),
                command.startTls(), command.sslEnabled());
        var value = settings.findById(companyId)
                .orElseGet(() -> new MemberSmtpSettings(companyId, protectedCommand));
        value.update(protectedCommand);
        // Upgrade legacy plaintext on explicit configuration save; never send it as plaintext fallback.
        if (value.getPassword() != null && !cipher.isEncrypted(value.getPassword())
                && (value.isEnabled() || cipher.isConfigured())) {
            value.setProtectedPassword(cipher.encrypt(value.getPassword(), companyId));
        }
        return view(settings.save(value));
    }

    public void test(MemberSmtpTestCommand command) {
        var company = organization.currentCompany();
        var config = settings.findById(company.getId())
                .filter(MemberSmtpSettings::isEnabled)
                .orElseThrow(() -> new IllegalStateException("message.member_card_delivery.sender_not_configured"));
        var customer = new Customer(company, "SMTP TEST", DocumentType.OTRO, "SMTP-TEST",
                null, null, command.toEmail(), null, CustomerRate.VENTA, java.math.BigDecimal.ZERO);
        var member = new Member(customer, "SMTP-TEST", java.time.LocalDate.now());
        sender.send(new MemberCardDelivery(
                member, command.toEmail(), command.subject(), command.body(),
                MemberCardCodeFormat.QR, java.time.Instant.now()));
    }
    // Sends a transient test message without creating a member-card delivery row.

    public record MemberSmtpSettingsView(
            boolean configured,
            boolean enabled,
            String host,
            int port,
            String username,
            String fromEmail,
            String fromName,
            boolean startTls,
            boolean sslEnabled,
            boolean passwordConfigured,
            boolean credentialsReady,
            boolean deliveryEnabled) {
    }

    private MemberSmtpSettingsView view(MemberSmtpSettings value) {
        return new MemberSmtpSettingsView(true, value.isEnabled(), value.getHost(), value.getPort(),
                value.getUsername(), value.getFromEmail(), value.getFromName(), value.isStartTls(),
                value.isSslEnabled(), value.getPassword() != null,
                credentialsReady(value),
                deliveryEnabled());
    }

    private boolean credentialsReady(MemberSmtpSettings value) {
        if (!cipher.isConfigured()) return false;
        try {
            cipher.decrypt(value.getPassword(), value.getCompanyId());
            return true;
        } catch (IllegalStateException unavailableCredential) {
            return false;
        }
    }

    private boolean deliveryEnabled() {
        return Boolean.TRUE.equals(environment.getProperty("tpv.members.card-delivery-enabled", Boolean.class, false));
    }

    public record MemberSmtpTestCommand(String toEmail, String subject, String body) {
    }
}
